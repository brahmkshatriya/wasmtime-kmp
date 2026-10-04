#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winioctl.h>
#include <direct.h>
#include <errno.h>
#include <fcntl.h>
#include <io.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include "windows_compat.h"

#undef open
#undef close
#undef mkdir
#undef fcntl
#undef fsync
#undef fchmod
#undef lstat
#undef geteuid
#undef realpath
#undef mkstemp

#define WKM_MAX_TRACKED_FDS 4096

static SRWLOCK wkm_lock = SRWLOCK_INIT;
static int wkm_fd_flags[WKM_MAX_TRACKED_FDS];
static HANDLE wkm_fd_mutex[WKM_MAX_TRACKED_FDS];

typedef struct wkm_symlink_reparse_buffer {
    DWORD ReparseTag;
    WORD ReparseDataLength;
    WORD Reserved;
    WORD SubstituteNameOffset;
    WORD SubstituteNameLength;
    WORD PrintNameOffset;
    WORD PrintNameLength;
    ULONG Flags;
    WCHAR PathBuffer[1];
} wkm_symlink_reparse_buffer_t;

static void set_errno_win32(DWORD error) {
    switch (error) {
        case ERROR_FILE_NOT_FOUND: case ERROR_PATH_NOT_FOUND: errno = ENOENT; break;
        case ERROR_ACCESS_DENIED: case ERROR_PRIVILEGE_NOT_HELD: errno = EACCES; break;
        case ERROR_ALREADY_EXISTS: case ERROR_FILE_EXISTS: errno = EEXIST; break;
        case ERROR_DIRECTORY: errno = ENOTDIR; break;
        case ERROR_DIR_NOT_EMPTY: errno = ENOTEMPTY; break;
        case ERROR_DISK_FULL: errno = ENOSPC; break;
        case ERROR_INVALID_NAME: case ERROR_INVALID_PARAMETER: errno = EINVAL; break;
        default: errno = EIO; break;
    }
}

static wchar_t *utf8_to_wide(const char *text) {
    if (text == NULL) { errno = EINVAL; return NULL; }
    int n = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, NULL, 0);
    if (n <= 0) { set_errno_win32(GetLastError()); return NULL; }
    wchar_t *out = (wchar_t *)calloc((size_t)n, sizeof(wchar_t));
    if (out == NULL) { errno = ENOMEM; return NULL; }
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text, -1, out, n) <= 0) {
        set_errno_win32(GetLastError()); free(out); return NULL;
    }
    return out;
}

static char *wide_to_utf8(const wchar_t *text) {
    int n = WideCharToMultiByte(CP_UTF8, 0, text, -1, NULL, 0, NULL, NULL);
    if (n <= 0) { set_errno_win32(GetLastError()); return NULL; }
    char *out = (char *)malloc((size_t)n);
    if (out == NULL) { errno = ENOMEM; return NULL; }
    if (WideCharToMultiByte(CP_UTF8, 0, text, -1, out, n, NULL, NULL) <= 0) {
        set_errno_win32(GetLastError()); free(out); return NULL;
    }
    return out;
}

static wchar_t *fd_path_wide(int fd) {
    intptr_t raw = _get_osfhandle(fd);
    if (raw == -1) { errno = EBADF; return NULL; }
    HANDLE handle = (HANDLE)raw;
    DWORD needed = GetFinalPathNameByHandleW(handle, NULL, 0, FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    if (needed == 0) { set_errno_win32(GetLastError()); return NULL; }
    wchar_t *out = (wchar_t *)calloc((size_t)needed + 2, sizeof(wchar_t));
    if (out == NULL) { errno = ENOMEM; return NULL; }
    if (GetFinalPathNameByHandleW(handle, out, needed + 1, FILE_NAME_NORMALIZED | VOLUME_NAME_DOS) == 0) {
        set_errno_win32(GetLastError()); free(out); return NULL;
    }
    return out;
}

static wchar_t *join_parent_path(int parent_fd, const char *path) {
    if (path == NULL || path[0] == '\0' || strchr(path, '\\') != NULL || strchr(path, ':') != NULL) {
        errno = EINVAL; return NULL;
    }
    wchar_t *parent = fd_path_wide(parent_fd);
    wchar_t *child = utf8_to_wide(path);
    if (parent == NULL || child == NULL) { free(parent); free(child); return NULL; }
    size_t pn = wcslen(parent), cn = wcslen(child);
    wchar_t *out = (wchar_t *)calloc(pn + cn + 2, sizeof(wchar_t));
    if (out == NULL) { free(parent); free(child); errno = ENOMEM; return NULL; }
    memcpy(out, parent, pn * sizeof(wchar_t));
    if (pn > 0 && parent[pn - 1] != L'\\' && parent[pn - 1] != L'/') out[pn++] = L'\\';
    memcpy(out + pn, child, (cn + 1) * sizeof(wchar_t));
    free(parent); free(child);
    return out;
}

static int is_reparse(HANDLE handle) {
    FILE_ATTRIBUTE_TAG_INFO info;
    if (!GetFileInformationByHandleEx(handle, FileAttributeTagInfo, &info, sizeof(info))) return -1;
    return (info.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
}

static DWORD desired_access(int flags, int directory) {
    if (directory) return GENERIC_READ;
    if ((flags & O_RDWR) == O_RDWR) return GENERIC_READ | GENERIC_WRITE;
    if (flags & O_WRONLY) return GENERIC_WRITE;
    return GENERIC_READ;
}

static DWORD creation_disposition(int flags) {
    if ((flags & O_CREAT) && (flags & O_EXCL)) return CREATE_NEW;
    if ((flags & O_CREAT) && (flags & O_TRUNC)) return CREATE_ALWAYS;
    if (flags & O_CREAT) return OPEN_ALWAYS;
    if (flags & O_TRUNC) return TRUNCATE_EXISTING;
    return OPEN_EXISTING;
}

static int handle_to_fd(HANDLE handle, int flags) {
    int crt_flags = O_BINARY;
    if ((flags & O_RDWR) == O_RDWR) crt_flags |= O_RDWR;
    else if (flags & O_WRONLY) crt_flags |= O_WRONLY;
    else crt_flags |= O_RDONLY;
    if (flags & O_APPEND) crt_flags |= O_APPEND;
    int fd = _open_osfhandle((intptr_t)handle, crt_flags);
    if (fd < 0) CloseHandle(handle);
    if (fd >= 0 && fd < WKM_MAX_TRACKED_FDS) {
        AcquireSRWLockExclusive(&wkm_lock); wkm_fd_flags[fd] = flags; ReleaseSRWLockExclusive(&wkm_lock);
    }
    return fd;
}

static int open_wide(const wchar_t *path, int flags, int mode) {
    (void)mode;
    int directory = (flags & O_DIRECTORY) != 0;
    DWORD attributes = FILE_ATTRIBUTE_NORMAL;
    if (directory) attributes |= FILE_FLAG_BACKUP_SEMANTICS;
    if (flags & O_NOFOLLOW) attributes |= FILE_FLAG_OPEN_REPARSE_POINT;
    DWORD share_mode = FILE_SHARE_READ | FILE_SHARE_WRITE;
    if (!directory) share_mode |= FILE_SHARE_DELETE;
    HANDLE handle = CreateFileW(
        path, desired_access(flags, directory),
        share_mode,
        NULL, creation_disposition(flags), attributes, NULL
    );
    if (handle == INVALID_HANDLE_VALUE) { set_errno_win32(GetLastError()); return -1; }
    if (flags & O_NOFOLLOW) {
        int reparse = is_reparse(handle);
        if (reparse != 0) {
            CloseHandle(handle); errno = reparse > 0 ? ELOOP : EIO; return -1;
        }
    }
    if (directory) {
        BY_HANDLE_FILE_INFORMATION info;
        if (!GetFileInformationByHandle(handle, &info) || !(info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) {
            CloseHandle(handle); errno = ENOTDIR; return -1;
        }
    }
    return handle_to_fd(handle, flags);
}

int wkm_open(const char *path, int flags, ...) {
    int mode = 0600;
    if (flags & O_CREAT) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); }
    wchar_t *wide = utf8_to_wide(path);
    if (wide == NULL) return -1;
    int fd = open_wide(wide, flags, mode);
    free(wide); return fd;
}

int wkm_openat(int parent_fd, const char *path, int flags, ...) {
    int mode = 0600;
    if (flags & O_CREAT) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); }
    wchar_t *wide = join_parent_path(parent_fd, path);
    if (wide == NULL) return -1;
    int fd = open_wide(wide, flags, mode);
    free(wide); return fd;
}

static uint64_t filetime_ticks(FILETIME value) {
    return ((uint64_t)value.dwHighDateTime << 32) | value.dwLowDateTime;
}
static time_t filetime_unix_seconds(FILETIME value) {
    uint64_t ticks = filetime_ticks(value);
    const uint64_t epoch = UINT64_C(116444736000000000);
    return ticks > epoch ? (time_t)((ticks - epoch) / UINT64_C(10000000)) : 0;
}

static int stat_handle(HANDLE handle, struct stat *st, int report_reparse) {
    BY_HANDLE_FILE_INFORMATION info;
    FILE_ATTRIBUTE_TAG_INFO tag;
    if (!GetFileInformationByHandle(handle, &info)) { set_errno_win32(GetLastError()); return -1; }
    memset(st, 0, sizeof(*st));
    int reparse = GetFileInformationByHandleEx(handle, FileAttributeTagInfo, &tag, sizeof(tag))
        && (tag.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT);
    if (report_reparse && reparse) st->st_mode = S_IFLNK | 0777;
    else if (info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) st->st_mode = S_IFDIR | 0700;
    else st->st_mode = S_IFREG | 0600;
    st->st_nlink = (short)info.nNumberOfLinks;
    st->st_size = ((int64_t)info.nFileSizeHigh << 32) | info.nFileSizeLow;
    st->st_dev = (dev_t)info.dwVolumeSerialNumber;
    st->st_ino = (ino_t)(((uint64_t)info.nFileIndexHigh << 32) | info.nFileIndexLow);
    st->st_atime = filetime_unix_seconds(info.ftLastAccessTime);
    st->st_mtime = filetime_unix_seconds(info.ftLastWriteTime);
    st->st_ctime = filetime_unix_seconds(info.ftCreationTime);
    return 0;
}

int wkm_fstatat(int parent_fd, const char *path, struct stat *st, int flags) {
    (void)flags;
    wchar_t *wide = join_parent_path(parent_fd, path);
    if (wide == NULL) return -1;
    HANDLE handle = CreateFileW(wide, FILE_READ_ATTRIBUTES,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL);
    free(wide);
    if (handle == INVALID_HANDLE_VALUE) { set_errno_win32(GetLastError()); return -1; }
    int rc = stat_handle(handle, st, 1);
    CloseHandle(handle); return rc;
}

DIR *wkm_fdopendir(int fd) {
    wchar_t *wide = fd_path_wide(fd);
    if (wide == NULL) { _close(fd); return NULL; }
    char *utf8 = wide_to_utf8(wide); free(wide); _close(fd);
    if (utf8 == NULL) return NULL;
    DIR *dir = opendir(utf8); free(utf8); return dir;
}

int wkm_mkdir(const char *path, int mode) { (void)mode; return _mkdir(path); }
int wkm_mkdirat(int parent_fd, const char *path, int mode) {
    (void)mode; wchar_t *wide = join_parent_path(parent_fd, path); if (!wide) return -1;
    BOOL ok = CreateDirectoryW(wide, NULL); if (!ok) set_errno_win32(GetLastError()); free(wide); return ok ? 0 : -1;
}
int wkm_unlinkat(int parent_fd, const char *path, int flags) {
    wchar_t *wide = join_parent_path(parent_fd, path); if (!wide) return -1;
    BOOL ok = (flags & AT_REMOVEDIR) ? RemoveDirectoryW(wide) : DeleteFileW(wide);
    if (!ok) set_errno_win32(GetLastError()); free(wide); return ok ? 0 : -1;
}
int wkm_renameat(int old_parent_fd, const char *old_path, int new_parent_fd, const char *new_path) {
    wchar_t *old_w = join_parent_path(old_parent_fd, old_path), *new_w = join_parent_path(new_parent_fd, new_path);
    if (!old_w || !new_w) { free(old_w); free(new_w); return -1; }
    BOOL ok = MoveFileExW(old_w, new_w, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
    if (!ok) set_errno_win32(GetLastError()); free(old_w); free(new_w); return ok ? 0 : -1;
}

int wkm_symlinkat(const char *target, int parent_fd, const char *path) {
    if (target == NULL || strchr(target, '\\') != NULL || strchr(target, ':') != NULL) {
        errno = EINVAL;
        return -1;
    }
    wchar_t *link_w = join_parent_path(parent_fd, path), *target_w = utf8_to_wide(target);
    if (!link_w || !target_w) { free(link_w); free(target_w); return -1; }
    DWORD flags = SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE;
    BOOL ok = CreateSymbolicLinkW(link_w, target_w, flags);
    if (!ok) { flags |= SYMBOLIC_LINK_FLAG_DIRECTORY; ok = CreateSymbolicLinkW(link_w, target_w, flags); }
    if (!ok) set_errno_win32(GetLastError()); free(link_w); free(target_w); return ok ? 0 : -1;
}

ssize_t wkm_readlinkat(int parent_fd, const char *path, char *buffer, size_t size) {
    wchar_t *wide = join_parent_path(parent_fd, path); if (!wide) return -1;
    HANDLE handle = CreateFileW(wide, 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        NULL, OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL);
    free(wide);
    if (handle == INVALID_HANDLE_VALUE) { set_errno_win32(GetLastError()); return -1; }
    BYTE raw[MAXIMUM_REPARSE_DATA_BUFFER_SIZE]; DWORD returned = 0;
    if (!DeviceIoControl(handle, FSCTL_GET_REPARSE_POINT, NULL, 0, raw, sizeof(raw), &returned, NULL)) {
        set_errno_win32(GetLastError()); CloseHandle(handle); return -1;
    }
    CloseHandle(handle);
    wkm_symlink_reparse_buffer_t *data = (wkm_symlink_reparse_buffer_t *)raw;
    if (data->ReparseTag != IO_REPARSE_TAG_SYMLINK) { errno = EINVAL; return -1; }
    USHORT off = data->PrintNameOffset / sizeof(WCHAR);
    USHORT len = data->PrintNameLength / sizeof(WCHAR);
    wchar_t *tmp = (wchar_t *)calloc((size_t)len + 1, sizeof(wchar_t));
    if (!tmp) { errno = ENOMEM; return -1; }
    memcpy(tmp, data->PathBuffer + off, len * sizeof(wchar_t));
    char *utf8 = wide_to_utf8(tmp); free(tmp); if (!utf8) return -1;
    size_t bytes = strlen(utf8); if (bytes > size) bytes = size;
    memcpy(buffer, utf8, bytes); free(utf8); return (ssize_t)bytes;
}

static uint64_t path_hash(const wchar_t *path) {
    uint64_t h = UINT64_C(1469598103934665603);
    for (; *path; path++) { wchar_t c = *path; if (c >= L'A' && c <= L'Z') c += 32; h ^= (uint16_t)c; h *= UINT64_C(1099511628211); }
    return h;
}
int wkm_flock(int fd, int operation) {
    if (fd < 0 || fd >= WKM_MAX_TRACKED_FDS) { errno = EBADF; return -1; }
    if (operation & LOCK_UN) {
        AcquireSRWLockExclusive(&wkm_lock); HANDLE mutex = wkm_fd_mutex[fd]; wkm_fd_mutex[fd] = NULL; ReleaseSRWLockExclusive(&wkm_lock);
        if (mutex) { ReleaseMutex(mutex); CloseHandle(mutex); } return 0;
    }
    AcquireSRWLockShared(&wkm_lock); HANDLE existing = wkm_fd_mutex[fd]; ReleaseSRWLockShared(&wkm_lock);
    if (existing) return 0;
    wchar_t *path = fd_path_wide(fd); if (!path) return -1;
    wchar_t name[96]; _snwprintf(name, 95, L"Local\\wasmtime-kmp-storage-%016llx", (unsigned long long)path_hash(path)); free(path);
    HANDLE mutex = CreateMutexW(NULL, FALSE, name); if (!mutex) { set_errno_win32(GetLastError()); return -1; }
    DWORD wait = WaitForSingleObject(mutex, (operation & LOCK_NB) ? 0 : INFINITE);
    if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED) { CloseHandle(mutex); errno = EWOULDBLOCK; return -1; }
    AcquireSRWLockExclusive(&wkm_lock); wkm_fd_mutex[fd] = mutex; ReleaseSRWLockExclusive(&wkm_lock); return 0;
}

int wkm_close(int fd) {
    if (fd >= 0 && fd < WKM_MAX_TRACKED_FDS) {
        AcquireSRWLockExclusive(&wkm_lock);
        HANDLE mutex = wkm_fd_mutex[fd]; wkm_fd_mutex[fd] = NULL; wkm_fd_flags[fd] = 0;
        ReleaseSRWLockExclusive(&wkm_lock);
        if (mutex) { ReleaseMutex(mutex); CloseHandle(mutex); }
    }
    return _close(fd);
}
int wkm_fcntl(int fd, int command, ...) {
    if (command != F_GETFL || fd < 0 || fd >= WKM_MAX_TRACKED_FDS) { errno = EINVAL; return -1; }
    AcquireSRWLockShared(&wkm_lock); int flags = wkm_fd_flags[fd]; ReleaseSRWLockShared(&wkm_lock); return flags;
}
int wkm_fsync(int fd) { return _commit(fd); }
int wkm_fchmod(int fd, int mode) { (void)fd; (void)mode; return 0; }
int wkm_lstat(const char *path, struct stat *st) { return stat(path, st); }
unsigned wkm_geteuid(void) { return 0; }
char *wkm_realpath(const char *path, char *resolved) { return _fullpath(resolved, path, resolved ? _MAX_PATH : 0); }
int wkm_mkstemp(char *template_path) {
    if (_mktemp_s(template_path, strlen(template_path) + 1) != 0) { errno = EIO; return -1; }
    return _open(template_path, O_CREAT | O_EXCL | O_RDWR | O_BINARY, _S_IREAD | _S_IWRITE);
}

#endif
