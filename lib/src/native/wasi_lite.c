#define _GNU_SOURCE
#include "wasi_lite.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <time.h>
#include <unistd.h>

#ifdef _WIN32
#include <windows.h>
#include <bcrypt.h>
#endif

#include "windows_compat.h"

#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif
#ifndef O_NOFOLLOW
#define O_NOFOLLOW 0
#endif

#define WASI_ESUCCESS 0
#define WASI_E2BIG 1
#define WASI_EACCES 2
#define WASI_EBADF 8
#define WASI_EEXIST 20
#define WASI_EFAULT 21
#define WASI_EFBIG 22
#define WASI_EINVAL 28
#define WASI_EIO 29
#define WASI_EISDIR 31
#define WASI_ENOENT 44
#define WASI_ENOSPC 51
#define WASI_ENOSYS 52
#define WASI_ENOTDIR 54
#define WASI_ENOTEMPTY 55
#define WASI_ENOTCAPABLE 76

#define WASI_FILETYPE_UNKNOWN 0
#define WASI_FILETYPE_CHARACTER_DEVICE 2
#define WASI_FILETYPE_DIRECTORY 3
#define WASI_FILETYPE_REGULAR_FILE 4
#define WASI_FILETYPE_SYMBOLIC_LINK 7

#define WASI_O_CREAT 1
#define WASI_O_DIRECTORY 2
#define WASI_O_EXCL 4
#define WASI_O_TRUNC 8
#define WASI_FDFLAG_APPEND 1
#define WASI_RIGHT_FD_READ (UINT64_C(1) << 1)
#define WASI_RIGHT_FD_WRITE (UINT64_C(1) << 6)

#define WASI_PREOPEN_FD 3
#define WASI_FIRST_DYNAMIC_FD 4
#define WASI_MAX_FDS 128
#define WASI_MAX_IOVECS 1024
#define WASI_MAX_SUBSCRIPTIONS 64
#define WASI_MAX_PATH_BYTES 4096

#define WASI_SUBSCRIPTION_SIZE 48
#define WASI_EVENT_SIZE 32
#define WASI_EVENTTYPE_CLOCK 0
#define WASI_SUBCLOCK_ABSTIME 1

#define ARRAY_LEN(a) (sizeof(a) / sizeof((a)[0]))

typedef struct wasi_lite_fd {
    int host_fd;
    int used;
    int is_dir;
    int readable;
    int writable;
} wasi_lite_fd_t;

struct wasmtime_kmp_wasi_lite {
    char *guest_path;
    int read_only;
    int root_fd;
    uint64_t max_host_call_bytes;
    uint64_t max_output_bytes;
    uint64_t output_bytes;
    uint64_t max_wasi_poll_millis;
    uint64_t storage_max_bytes;
    uint64_t storage_used_bytes;
    uint64_t storage_max_entries;
    uint64_t storage_entries;
    uint64_t storage_max_file_bytes;
    wasi_lite_fd_t fds[WASI_MAX_FDS];
};

typedef struct wasi_memory_view {
    uint8_t *data;
    size_t size;
} wasi_memory_view_t;

static uint16_t errno_to_wasi(int error) {
    switch (error) {
        case 0: return WASI_ESUCCESS;
        case EACCES: case EPERM: return WASI_EACCES;
        case EBADF: return WASI_EBADF;
        case EEXIST: return WASI_EEXIST;
        case EFAULT: return WASI_EFAULT;
        case EFBIG: return WASI_EFBIG;
        case EINVAL: return WASI_EINVAL;
        case EIO: return WASI_EIO;
        case EISDIR: return WASI_EISDIR;
        case ENOENT: return WASI_ENOENT;
        case ENOSPC: return WASI_ENOSPC;
        case ENOSYS: return WASI_ENOSYS;
        case ENOTDIR: return WASI_ENOTDIR;
        case ENOTEMPTY: return WASI_ENOTEMPTY;
        default: return WASI_EIO;
    }
}

static void result_errno(wasmtime_val_t *results, uint16_t value) {
    results[0].kind = WASMTIME_I32;
    results[0].of.i32 = value;
}

static int memory_view(wasmtime_caller_t *caller, wasi_memory_view_t *out) {
    wasmtime_extern_t item;
    if (!wasmtime_caller_export_get(caller, "memory", 6, &item)
        || item.kind != WASMTIME_EXTERN_MEMORY) {
        return 0;
    }
    wasmtime_context_t *context = wasmtime_caller_context(caller);
    out->data = wasmtime_memory_data(context, &item.of.memory);
    out->size = wasmtime_memory_data_size(context, &item.of.memory);
    return out->data != NULL;
}

static int memory_range(
    const wasi_memory_view_t *memory,
    uint32_t offset,
    size_t length,
    uint8_t **out
) {
    if ((uint64_t) offset + length > memory->size) return 0;
    *out = memory->data + offset;
    return 1;
}

static uint16_t load_u16(const uint8_t *p) {
    return (uint16_t) p[0] | ((uint16_t) p[1] << 8);
}

static uint32_t load_u32(const uint8_t *p) {
    return (uint32_t) p[0]
        | ((uint32_t) p[1] << 8)
        | ((uint32_t) p[2] << 16)
        | ((uint32_t) p[3] << 24);
}

static uint64_t load_u64(const uint8_t *p) {
    uint64_t value = 0;
    for (int i = 7; i >= 0; i--) value = (value << 8) | p[i];
    return value;
}

static void store_u16(uint8_t *p, uint16_t value) {
    p[0] = (uint8_t) value;
    p[1] = (uint8_t) (value >> 8);
}

static void store_u32(uint8_t *p, uint32_t value) {
    p[0] = (uint8_t) value;
    p[1] = (uint8_t) (value >> 8);
    p[2] = (uint8_t) (value >> 16);
    p[3] = (uint8_t) (value >> 24);
}

static void store_u64(uint8_t *p, uint64_t value) {
    for (int i = 0; i < 8; i++) {
        p[i] = (uint8_t) value;
        value >>= 8;
    }
}

static int path_component_safe(const char *part) {
    return part[0] != '\0' && strcmp(part, ".") != 0 && strcmp(part, "..") != 0;
}

static uint16_t copy_guest_path(
    const wasi_memory_view_t *memory,
    uint32_t pointer,
    uint32_t length,
    char **out
) {
    if (length == 0 || length > WASI_MAX_PATH_BYTES
        || (uint64_t) pointer + length > memory->size) return WASI_EINVAL;
    const uint8_t *source = memory->data + pointer;
    // kotlinx-io 0.9.1 includes its trailing NUL in Preview-1 path lengths. Accept
    // exactly that compatibility form while continuing to reject embedded NUL bytes.
    if (length > 0 && source[length - 1] == 0) length--;
    if (length == 0) return WASI_EINVAL;
    if (source[0] == '/') return WASI_ENOTCAPABLE;
    char *path = malloc((size_t) length + 1);
    if (path == NULL) return WASI_EIO;
    for (uint32_t i = 0; i < length; i++) {
        if (source[i] == 0) {
            free(path);
            return WASI_EINVAL;
        }
        path[i] = (char) source[i];
    }
    path[length] = '\0';

    char *check = strdup(path);
    if (check == NULL) {
        free(path);
        return WASI_EIO;
    }
    char *save = NULL;
    for (char *part = strtok_r(check, "/", &save); part != NULL; part = strtok_r(NULL, "/", &save)) {
        if (!path_component_safe(part)) {
            free(check);
            free(path);
            return WASI_ENOTCAPABLE;
        }
    }
    free(check);
    *out = path;
    return WASI_ESUCCESS;
}

/* Opens the directory containing `path` without following symlinks in any
 * intermediate component. `leaf_out` points inside a separately allocated
 * string owned by the caller. */
static uint16_t open_parent(
    wasmtime_kmp_wasi_lite_t *state,
    char *path,
    int *parent_out,
    char **leaf_out
) {
    if (state == NULL || state->root_fd < 0) return WASI_EBADF;
    int current = dup(state->root_fd);
    if (current < 0) return errno_to_wasi(errno);

    char *last = strrchr(path, '/');
    char *leaf = path;
    if (last != NULL) {
        *last = '\0';
        leaf = last + 1;
        char *save = NULL;
        for (char *part = strtok_r(path, "/", &save); part != NULL; part = strtok_r(NULL, "/", &save)) {
            int next = openat(current, part, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
            if (next < 0) {
                uint16_t result = errno_to_wasi(errno);
                close(current);
                return result;
            }
            close(current);
            current = next;
        }
    }
    if (!path_component_safe(leaf)) {
        close(current);
        return WASI_ENOTCAPABLE;
    }
    *parent_out = current;
    *leaf_out = leaf;
    return WASI_ESUCCESS;
}

static int quota_can_add(
    const wasmtime_kmp_wasi_lite_t *state,
    uint64_t bytes,
    uint64_t entries
) {
    if (state == NULL) return 0;
    if (UINT64_MAX - state->storage_used_bytes < bytes
        || UINT64_MAX - state->storage_entries < entries) return 0;
    if (state->storage_max_bytes > 0 && state->storage_used_bytes + bytes > state->storage_max_bytes) return 0;
    if (state->storage_max_entries > 0 && state->storage_entries + entries > state->storage_max_entries) return 0;
    return 1;
}

static void quota_add(wasmtime_kmp_wasi_lite_t *state, uint64_t bytes, uint64_t entries) {
    state->storage_used_bytes += bytes;
    state->storage_entries += entries;
}

static void quota_remove(wasmtime_kmp_wasi_lite_t *state, uint64_t bytes, uint64_t entries) {
    state->storage_used_bytes = bytes > state->storage_used_bytes ? 0 : state->storage_used_bytes - bytes;
    state->storage_entries = entries > state->storage_entries ? 0 : state->storage_entries - entries;
}

static int scan_storage_directory(
    int root_fd,
    uint64_t *bytes,
    uint64_t *entries,
    uint64_t max_bytes,
    uint64_t max_entries,
    uint64_t max_file_bytes,
    unsigned depth
) {
    if (depth > 64) return 0;
    int duplicate = dup(root_fd);
    if (duplicate < 0) return 0;
    DIR *dir = fdopendir(duplicate);
    if (dir == NULL) { close(duplicate); return 0; }
    int ok = 1;
    struct dirent *item;
    while ((item = readdir(dir)) != NULL) {
        if (strcmp(item->d_name, ".") == 0 || strcmp(item->d_name, "..") == 0) continue;
        struct stat st;
        if (fstatat(root_fd, item->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0) { ok = 0; break; }
        if (*entries == UINT64_MAX) { ok = 0; break; }
        (*entries)++;
        if (max_entries > 0 && *entries > max_entries) { ok = 0; break; }
        if (S_ISREG(st.st_mode)) {
            if (st.st_nlink != 1 || st.st_size < 0
                || (max_file_bytes > 0 && (uint64_t) st.st_size > max_file_bytes)
                || UINT64_MAX - *bytes < (uint64_t) st.st_size) {
                ok = 0; break;
            }
            *bytes += (uint64_t) st.st_size;
            if (max_bytes > 0 && *bytes > max_bytes) { ok = 0; break; }
        } else if (S_ISDIR(st.st_mode)) {
            int child = openat(root_fd, item->d_name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
            if (child < 0 || !scan_storage_directory(
                    child, bytes, entries, max_bytes, max_entries, max_file_bytes, depth + 1
                )) {
                if (child >= 0) close(child);
                ok = 0; break;
            }
            close(child);
        } else if (S_ISLNK(st.st_mode)) {
            if (st.st_size < 0 || UINT64_MAX - *bytes < (uint64_t) st.st_size) { ok = 0; break; }
            *bytes += (uint64_t) st.st_size;
            if (max_bytes > 0 && *bytes > max_bytes) { ok = 0; break; }
        } else {
            /* FIFOs, devices, and sockets are ambient host capabilities. */
            ok = 0;
            break;
        }
    }
    closedir(dir);
    return ok;
}

static void update_file_size_after_write(
    wasmtime_kmp_wasi_lite_t *state,
    int host_fd,
    uint64_t old_size
) {
    struct stat st;
    if (fstat(host_fd, &st) != 0 || st.st_size < 0) return;
    uint64_t new_size = (uint64_t) st.st_size;
    if (new_size > old_size) quota_add(state, new_size - old_size, 0);
    else if (old_size > new_size) quota_remove(state, old_size - new_size, 0);
}

static int write_sanitized(int fd, const uint8_t *source, size_t length) {
    uint8_t buffer[4096];
    size_t offset = 0;
    while (offset < length) {
        size_t chunk = length - offset;
        if (chunk > sizeof(buffer)) chunk = sizeof(buffer);
        for (size_t i = 0; i < chunk; i++) {
            uint8_t value = source[offset + i];
            /* Neutralize C0 controls/DEL (notably ESC) but preserve UTF-8 bytes. */
            buffer[i] = (value == '\n' || value == '\r' || value == '\t'
                || (value >= 0x20 && value != 0x7f)) ? value : (uint8_t) '?';
        }
        size_t written = 0;
        while (written < chunk) {
            ssize_t count = write(fd, buffer + written, chunk - written);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) return 0;
            written += (size_t) count;
        }
        offset += chunk;
    }
    return 1;
}

static int allocate_fd(wasmtime_kmp_wasi_lite_t *state, int host_fd, int is_dir, int read, int write) {
    for (int fd = WASI_FIRST_DYNAMIC_FD; fd < WASI_MAX_FDS; fd++) {
        if (!state->fds[fd].used) {
            state->fds[fd] = (wasi_lite_fd_t) {
                .host_fd = host_fd,
                .used = 1,
                .is_dir = is_dir,
                .readable = read,
                .writable = write,
            };
            return fd;
        }
    }
    return -1;
}

static wasi_lite_fd_t *lookup_fd(wasmtime_kmp_wasi_lite_t *state, int fd) {
    if (state == NULL || fd < 0 || fd >= WASI_MAX_FDS || !state->fds[fd].used) return NULL;
    return &state->fds[fd];
}

static uint8_t wasi_filetype(mode_t mode) {
    if (S_ISDIR(mode)) return WASI_FILETYPE_DIRECTORY;
    if (S_ISREG(mode)) return WASI_FILETYPE_REGULAR_FILE;
    if (S_ISLNK(mode)) return WASI_FILETYPE_SYMBOLIC_LINK;
    if (S_ISCHR(mode)) return WASI_FILETYPE_CHARACTER_DEVICE;
    return WASI_FILETYPE_UNKNOWN;
}

static uint64_t stat_time_ns(time_t seconds, long nanoseconds) {
    return (uint64_t) seconds * UINT64_C(1000000000) + (uint64_t) nanoseconds;
}

static void write_filestat(uint8_t *target, const struct stat *st) {
    memset(target, 0, 64);
    store_u64(target + 0, (uint64_t) st->st_dev);
    store_u64(target + 8, (uint64_t) st->st_ino);
    target[16] = wasi_filetype(st->st_mode);
    store_u64(target + 24, (uint64_t) st->st_nlink);
    store_u64(target + 32, (uint64_t) st->st_size);
#if defined(__APPLE__)
    store_u64(target + 40, stat_time_ns(st->st_atimespec.tv_sec, st->st_atimespec.tv_nsec));
    store_u64(target + 48, stat_time_ns(st->st_mtimespec.tv_sec, st->st_mtimespec.tv_nsec));
    store_u64(target + 56, stat_time_ns(st->st_ctimespec.tv_sec, st->st_ctimespec.tv_nsec));
#elif defined(_WIN32)
    store_u64(target + 40, stat_time_ns(st->st_atime, 0));
    store_u64(target + 48, stat_time_ns(st->st_mtime, 0));
    store_u64(target + 56, stat_time_ns(st->st_ctime, 0));
#else
    store_u64(target + 40, stat_time_ns(st->st_atim.tv_sec, st->st_atim.tv_nsec));
    store_u64(target + 48, stat_time_ns(st->st_mtim.tv_sec, st->st_mtim.tv_nsec));
    store_u64(target + 56, stat_time_ns(st->st_ctim.tv_sec, st->st_ctim.tv_nsec));
#endif
}

static wasm_trap_t *wasi_clock_time_get(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) env; (void) nargs; (void) nresults;
    wasi_memory_view_t memory;
    if (!memory_view(caller, &memory)) { result_errno(results, WASI_EFAULT); return NULL; }
    uint8_t *target;
    if (!memory_range(&memory, (uint32_t) args[2].of.i32, 8, &target)) {
        result_errno(results, WASI_EFAULT); return NULL;
    }
    clockid_t clock_id;
    switch (args[0].of.i32) {
        case 0: clock_id = CLOCK_REALTIME; break;
        case 1: clock_id = CLOCK_MONOTONIC; break;
#ifdef CLOCK_PROCESS_CPUTIME_ID
        case 2: clock_id = CLOCK_PROCESS_CPUTIME_ID; break;
#endif
#ifdef CLOCK_THREAD_CPUTIME_ID
        case 3: clock_id = CLOCK_THREAD_CPUTIME_ID; break;
#endif
        default: result_errno(results, WASI_EINVAL); return NULL;
    }
    struct timespec ts;
    if (clock_gettime(clock_id, &ts) != 0) { result_errno(results, errno_to_wasi(errno)); return NULL; }
    store_u64(target, stat_time_ns(ts.tv_sec, ts.tv_nsec));
    result_errno(results, WASI_ESUCCESS);
    return NULL;
}

static wasm_trap_t *wasi_random_get(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    wasi_memory_view_t memory;
    uint32_t pointer = (uint32_t) args[0].of.i32;
    uint32_t length = (uint32_t) args[1].of.i32;
    if (state != NULL && state->max_host_call_bytes > 0 && length > state->max_host_call_bytes) {
        result_errno(results, WASI_E2BIG); return NULL;
    }
    uint8_t *target;
    if (!memory_view(caller, &memory) || !memory_range(&memory, pointer, length, &target)) {
        result_errno(results, WASI_EFAULT); return NULL;
    }
#ifdef _WIN32
    if (length > ULONG_MAX
        || BCryptGenRandom(NULL, target, (ULONG) length, BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) {
        result_errno(results, WASI_EIO); return NULL;
    }
#elif defined(__APPLE__)
    arc4random_buf(target, length);
#else
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) { result_errno(results, errno_to_wasi(errno)); return NULL; }
    size_t done = 0;
    while (done < length) {
        ssize_t count = read(fd, target + done, length - done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { close(fd); result_errno(results, WASI_EIO); return NULL; }
        done += (size_t) count;
    }
    close(fd);
#endif
    result_errno(results, WASI_ESUCCESS);
    return NULL;
}

static wasm_trap_t *wasi_fd_prestat_get(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    if (args[0].of.i32 != WASI_PREOPEN_FD || state == NULL || state->root_fd < 0) {
        result_errno(results, WASI_EBADF); return NULL;
    }
    wasi_memory_view_t memory; uint8_t *target;
    if (!memory_view(caller, &memory) || !memory_range(&memory, (uint32_t) args[1].of.i32, 8, &target)) {
        result_errno(results, WASI_EFAULT); return NULL;
    }
    memset(target, 0, 8);
    target[0] = 0;
    store_u32(target + 4, (uint32_t) strlen(state->guest_path));
    result_errno(results, WASI_ESUCCESS);
    return NULL;
}

static wasm_trap_t *wasi_fd_prestat_dir_name(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    if (args[0].of.i32 != WASI_PREOPEN_FD || state == NULL || state->root_fd < 0) {
        result_errno(results, WASI_EBADF); return NULL;
    }
    size_t needed = strlen(state->guest_path);
    if ((uint32_t) args[2].of.i32 < needed) { result_errno(results, WASI_EINVAL); return NULL; }
    wasi_memory_view_t memory; uint8_t *target;
    if (!memory_view(caller, &memory) || !memory_range(&memory, (uint32_t) args[1].of.i32, needed, &target)) {
        result_errno(results, WASI_EFAULT); return NULL;
    }
    memcpy(target, state->guest_path, needed);
    result_errno(results, WASI_ESUCCESS);
    return NULL;
}

static wasm_trap_t *wasi_fd_close(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) caller; (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    int fd = args[0].of.i32;
    if (fd < WASI_FIRST_DYNAMIC_FD) { result_errno(results, WASI_EBADF); return NULL; }
    wasi_lite_fd_t *entry = lookup_fd(state, fd);
    if (entry == NULL) { result_errno(results, WASI_EBADF); return NULL; }
    close(entry->host_fd);
    memset(entry, 0, sizeof(*entry));
    entry->host_fd = -1;
    result_errno(results, WASI_ESUCCESS);
    return NULL;
}

static wasm_trap_t *wasi_fd_sync(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) caller; (void) nargs; (void) nresults;
    wasi_lite_fd_t *entry = lookup_fd(env, args[0].of.i32);
    if (entry == NULL) { result_errno(results, WASI_EBADF); return NULL; }
    result_errno(results, fsync(entry->host_fd) == 0 ? WASI_ESUCCESS : errno_to_wasi(errno));
    return NULL;
}

static wasm_trap_t *wasi_fd_read(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    int guest_fd = args[0].of.i32;
    uint32_t iovs = (uint32_t) args[1].of.i32;
    uint32_t iovs_len = (uint32_t) args[2].of.i32;
    uint32_t nread_ptr = (uint32_t) args[3].of.i32;
    wasi_memory_view_t memory; uint8_t *nread;
    if (iovs_len > WASI_MAX_IOVECS) { result_errno(results, WASI_E2BIG); return NULL; }
    if (!memory_view(caller, &memory) || !memory_range(&memory, nread_ptr, 4, &nread)
        || (uint64_t) iovs + (uint64_t) iovs_len * 8 > memory.size) {
        result_errno(results, WASI_EFAULT); return NULL;
    }
    if (guest_fd == 0) { store_u32(nread, 0); result_errno(results, WASI_ESUCCESS); return NULL; }
    wasi_lite_fd_t *entry = lookup_fd(state, guest_fd);
    if (entry == NULL || !entry->readable || entry->is_dir) { result_errno(results, WASI_EBADF); return NULL; }
    uint64_t requested = 0;
    for (uint32_t index = 0; index < iovs_len; index++) {
        const uint8_t *iov = memory.data + iovs + index * 8;
        uint32_t pointer = load_u32(iov);
        uint32_t length = load_u32(iov + 4);
        uint8_t *ignored;
        if (!memory_range(&memory, pointer, length, &ignored) || UINT64_MAX - requested < length) {
            result_errno(results, WASI_EFAULT); return NULL;
        }
        requested += length;
    }
    if (state->max_host_call_bytes > 0 && requested > state->max_host_call_bytes) {
        result_errno(results, WASI_E2BIG); return NULL;
    }
    uint32_t total = 0;
    for (uint32_t index = 0; index < iovs_len; index++) {
        const uint8_t *iov = memory.data + iovs + index * 8;
        uint32_t pointer = load_u32(iov);
        uint32_t length = load_u32(iov + 4);
        uint8_t *target;
        if (!memory_range(&memory, pointer, length, &target)) { result_errno(results, WASI_EFAULT); return NULL; }
        ssize_t count;
        do { count = read(entry->host_fd, target, length); } while (count < 0 && errno == EINTR);
        if (count < 0) { result_errno(results, errno_to_wasi(errno)); return NULL; }
        total += (uint32_t) count;
        if ((uint32_t) count < length) break;
    }
    store_u32(nread, total);
    result_errno(results, WASI_ESUCCESS);
    return NULL;
}

static wasm_trap_t *wasi_fd_write(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    int guest_fd = args[0].of.i32;
    uint32_t iovs = (uint32_t) args[1].of.i32;
    uint32_t iovs_len = (uint32_t) args[2].of.i32;
    uint32_t nwritten_ptr = (uint32_t) args[3].of.i32;
    wasi_memory_view_t memory; uint8_t *nwritten;
    if (iovs_len > WASI_MAX_IOVECS) { result_errno(results, WASI_E2BIG); return NULL; }
    if (!memory_view(caller, &memory) || !memory_range(&memory, nwritten_ptr, 4, &nwritten)
        || (uint64_t) iovs + (uint64_t) iovs_len * 8 > memory.size) {
        result_errno(results, WASI_EFAULT); return NULL;
    }
    uint64_t requested = 0;
    for (uint32_t index = 0; index < iovs_len; index++) {
        const uint8_t *iov = memory.data + iovs + index * 8;
        uint32_t pointer = load_u32(iov);
        uint32_t length = load_u32(iov + 4);
        uint8_t *ignored;
        if (!memory_range(&memory, pointer, length, &ignored) || UINT64_MAX - requested < length) {
            result_errno(results, WASI_EFAULT); return NULL;
        }
        requested += length;
    }
    if (state->max_host_call_bytes > 0 && requested > state->max_host_call_bytes) {
        result_errno(results, WASI_E2BIG); return NULL;
    }

    int host_fd;
    uint64_t old_size = 0;
    int is_output = guest_fd == 1 || guest_fd == 2;
    if (is_output) {
        host_fd = guest_fd == 1 ? STDOUT_FILENO : STDERR_FILENO;
        if (state->max_output_bytes > 0
            && (UINT64_MAX - state->output_bytes < requested
                || state->output_bytes + requested > state->max_output_bytes)) {
            result_errno(results, WASI_ENOTCAPABLE); return NULL;
        }
    } else {
        wasi_lite_fd_t *entry = lookup_fd(state, guest_fd);
        if (entry == NULL || !entry->writable || entry->is_dir) { result_errno(results, WASI_EBADF); return NULL; }
        host_fd = entry->host_fd;
        struct stat st;
        if (fstat(host_fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < 0) {
            result_errno(results, WASI_ENOTCAPABLE); return NULL;
        }
        old_size = (uint64_t) st.st_size;
        off_t current = lseek(host_fd, 0, SEEK_CUR);
        if (current < 0) { result_errno(results, errno_to_wasi(errno)); return NULL; }
        int flags = fcntl(host_fd, F_GETFL);
        uint64_t start = flags >= 0 && (flags & O_APPEND) ? old_size : (uint64_t) current;
        if (UINT64_MAX - start < requested) { result_errno(results, WASI_EFBIG); return NULL; }
        uint64_t end = start + requested;
        uint64_t projected = end > old_size ? end : old_size;
        uint64_t growth = projected - old_size;
        if ((state->storage_max_file_bytes > 0 && projected > state->storage_max_file_bytes)
            || !quota_can_add(state, growth, 0)) {
            result_errno(results, WASI_ENOSPC); return NULL;
        }
    }

    uint32_t total = 0;
    for (uint32_t index = 0; index < iovs_len; index++) {
        const uint8_t *iov = memory.data + iovs + index * 8;
        uint32_t pointer = load_u32(iov);
        uint32_t length = load_u32(iov + 4);
        uint8_t *source;
        if (!memory_range(&memory, pointer, length, &source)) { result_errno(results, WASI_EFAULT); return NULL; }
        if (is_output) {
            if (!write_sanitized(host_fd, source, length)) { result_errno(results, WASI_EIO); return NULL; }
        } else {
            size_t offset = 0;
            while (offset < length) {
                ssize_t count = write(host_fd, source + offset, length - offset);
                if (count < 0 && errno == EINTR) continue;
                if (count < 0) {
                    update_file_size_after_write(state, host_fd, old_size);
                    result_errno(results, errno_to_wasi(errno)); return NULL;
                }
                offset += (size_t) count;
            }
        }
        total += length;
    }
    if (is_output) state->output_bytes += total;
    else update_file_size_after_write(state, host_fd, old_size);
    store_u32(nwritten, total);
    result_errno(results, WASI_ESUCCESS);
    return NULL;
}

static wasm_trap_t *wasi_path_open(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    if (args[0].of.i32 != WASI_PREOPEN_FD || state == NULL || state->root_fd < 0) {
        result_errno(results, WASI_EBADF); return NULL;
    }
    wasi_memory_view_t memory;
    if (!memory_view(caller, &memory)) { result_errno(results, WASI_EFAULT); return NULL; }
    char *path = NULL;
    uint16_t status = copy_guest_path(&memory, (uint32_t) args[2].of.i32, (uint32_t) args[3].of.i32, &path);
    if (status != WASI_ESUCCESS) { result_errno(results, status); return NULL; }
    int parent = -1; char *leaf = NULL;
    status = open_parent(state, path, &parent, &leaf);
    if (status != WASI_ESUCCESS) { free(path); result_errno(results, status); return NULL; }

    uint32_t oflags = (uint32_t) args[4].of.i32;
    uint64_t rights = (uint64_t) args[5].of.i64;
    uint32_t fdflags = (uint32_t) args[7].of.i32;
    int wants_read = (rights & WASI_RIGHT_FD_READ) != 0;
    int wants_write = (rights & WASI_RIGHT_FD_WRITE) != 0 || (oflags & (WASI_O_CREAT | WASI_O_TRUNC)) != 0;
    if (state->read_only && wants_write) {
        close(parent); free(path); result_errno(results, WASI_ENOTCAPABLE); return NULL;
    }

    struct stat before = {0};
    int existed = fstatat(parent, leaf, &before, AT_SYMLINK_NOFOLLOW) == 0;
    if (!existed && errno != ENOENT) {
        int saved = errno; close(parent); free(path); result_errno(results, errno_to_wasi(saved)); return NULL;
    }
    if (existed && S_ISLNK(before.st_mode)) {
        close(parent); free(path); result_errno(results, WASI_ENOTCAPABLE); return NULL;
    }
    if (!existed && (oflags & WASI_O_CREAT) && !quota_can_add(state, 0, 1)) {
        close(parent); free(path); result_errno(results, WASI_ENOSPC); return NULL;
    }

    int flags = O_CLOEXEC | O_NOFOLLOW;
    if (wants_read && wants_write) flags |= O_RDWR;
    else if (wants_write) flags |= O_WRONLY;
    else flags |= O_RDONLY;
    if (oflags & WASI_O_CREAT) flags |= O_CREAT;
    if (oflags & WASI_O_EXCL) flags |= O_EXCL;
    if (oflags & WASI_O_TRUNC) flags |= O_TRUNC;
    if (oflags & WASI_O_DIRECTORY) flags |= O_DIRECTORY;
    if (fdflags & WASI_FDFLAG_APPEND) flags |= O_APPEND;

    int host_fd = openat(parent, leaf, flags, 0600);
    int saved_errno = errno;
    close(parent);
    free(path);
    if (host_fd < 0) { result_errno(results, errno_to_wasi(saved_errno)); return NULL; }
    struct stat st;
    if (fstat(host_fd, &st) != 0) {
        saved_errno = errno; close(host_fd); result_errno(results, errno_to_wasi(saved_errno)); return NULL;
    }
    if (!S_ISREG(st.st_mode) && !S_ISDIR(st.st_mode)) {
        close(host_fd); result_errno(results, WASI_ENOTCAPABLE); return NULL;
    }
    if (S_ISREG(st.st_mode) && (st.st_nlink != 1 || st.st_size < 0
        || (state->storage_max_file_bytes > 0 && (uint64_t) st.st_size > state->storage_max_file_bytes))) {
        close(host_fd); result_errno(results, WASI_ENOSPC); return NULL;
    }
    if (!existed) quota_add(state, S_ISREG(st.st_mode) ? (uint64_t) st.st_size : 0, 1);
    else if ((oflags & WASI_O_TRUNC) && S_ISREG(before.st_mode) && before.st_size > st.st_size) {
        quota_remove(state, (uint64_t) (before.st_size - st.st_size), 0);
    }
    int guest_fd = allocate_fd(state, host_fd, S_ISDIR(st.st_mode), wants_read, wants_write);
    if (guest_fd < 0) { close(host_fd); result_errno(results, WASI_E2BIG); return NULL; }
    uint8_t *opened;
    if (!memory_range(&memory, (uint32_t) args[8].of.i32, 4, &opened)) {
        close(host_fd); memset(&state->fds[guest_fd], 0, sizeof(state->fds[guest_fd]));
        result_errno(results, WASI_EFAULT); return NULL;
    }
    store_u32(opened, (uint32_t) guest_fd);
    result_errno(results, WASI_ESUCCESS);
    return NULL;
}

static wasm_trap_t *wasi_path_filestat_get(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    if (args[0].of.i32 != WASI_PREOPEN_FD || state == NULL || state->root_fd < 0) {
        result_errno(results, WASI_EBADF); return NULL;
    }
    wasi_memory_view_t memory;
    if (!memory_view(caller, &memory)) { result_errno(results, WASI_EFAULT); return NULL; }
    char *path = NULL;
    uint16_t status = copy_guest_path(&memory, (uint32_t) args[2].of.i32, (uint32_t) args[3].of.i32, &path);
    if (status != WASI_ESUCCESS) { result_errno(results, status); return NULL; }
    int parent = -1; char *leaf = NULL;
    status = open_parent(state, path, &parent, &leaf);
    if (status != WASI_ESUCCESS) { free(path); result_errno(results, status); return NULL; }
    struct stat st;
    int rc = fstatat(parent, leaf, &st, AT_SYMLINK_NOFOLLOW);
    int saved_errno = errno;
    close(parent); free(path);
    if (rc != 0) { result_errno(results, errno_to_wasi(saved_errno)); return NULL; }
    uint8_t *target;
    if (!memory_range(&memory, (uint32_t) args[4].of.i32, 64, &target)) {
        result_errno(results, WASI_EFAULT); return NULL;
    }
    write_filestat(target, &st);
    result_errno(results, WASI_ESUCCESS);
    return NULL;
}

static uint16_t path_parent_and_leaf(
    wasmtime_kmp_wasi_lite_t *state,
    const wasi_memory_view_t *memory,
    uint32_t pointer,
    uint32_t length,
    char **path_out,
    int *parent_out,
    char **leaf_out
) {
    char *path = NULL;
    uint16_t status = copy_guest_path(memory, pointer, length, &path);
    if (status != WASI_ESUCCESS) return status;
    status = open_parent(state, path, parent_out, leaf_out);
    if (status != WASI_ESUCCESS) { free(path); return status; }
    *path_out = path;
    return WASI_ESUCCESS;
}

static wasm_trap_t *wasi_path_create_directory(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    if (state == NULL || state->read_only || args[0].of.i32 != WASI_PREOPEN_FD) {
        result_errno(results, state != NULL && state->read_only ? WASI_ENOTCAPABLE : WASI_EBADF); return NULL;
    }
    if (!quota_can_add(state, 0, 1)) { result_errno(results, WASI_ENOSPC); return NULL; }
    wasi_memory_view_t memory; if (!memory_view(caller, &memory)) { result_errno(results, WASI_EFAULT); return NULL; }
    char *path = NULL, *leaf = NULL; int parent = -1;
    uint16_t status = path_parent_and_leaf(state, &memory, (uint32_t) args[1].of.i32, (uint32_t) args[2].of.i32, &path, &parent, &leaf);
    if (status == 0) {
        int rc = mkdirat(parent, leaf, 0700); int e = errno;
        if (rc == 0) quota_add(state, 0, 1);
        close(parent); free(path);
        status = rc == 0 ? 0 : errno_to_wasi(e);
    }
    result_errno(results, status); return NULL;
}

static wasm_trap_t *wasi_path_remove_directory(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    if (state == NULL || state->read_only || args[0].of.i32 != WASI_PREOPEN_FD) {
        result_errno(results, state != NULL && state->read_only ? WASI_ENOTCAPABLE : WASI_EBADF); return NULL;
    }
    wasi_memory_view_t memory; if (!memory_view(caller, &memory)) { result_errno(results, WASI_EFAULT); return NULL; }
    char *path = NULL, *leaf = NULL; int parent = -1;
    uint16_t status = path_parent_and_leaf(state, &memory, (uint32_t) args[1].of.i32, (uint32_t) args[2].of.i32, &path, &parent, &leaf);
    if (status == 0) {
        int rc = unlinkat(parent, leaf, AT_REMOVEDIR); int e = errno;
        if (rc == 0) quota_remove(state, 0, 1);
        close(parent); free(path);
        status = rc == 0 ? 0 : errno_to_wasi(e);
    }
    result_errno(results, status); return NULL;
}

static wasm_trap_t *wasi_path_unlink_file(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    if (state == NULL || state->read_only || args[0].of.i32 != WASI_PREOPEN_FD) {
        result_errno(results, state != NULL && state->read_only ? WASI_ENOTCAPABLE : WASI_EBADF); return NULL;
    }
    wasi_memory_view_t memory; if (!memory_view(caller, &memory)) { result_errno(results, WASI_EFAULT); return NULL; }
    char *path = NULL, *leaf = NULL; int parent = -1;
    uint16_t status = path_parent_and_leaf(state, &memory, (uint32_t) args[1].of.i32, (uint32_t) args[2].of.i32, &path, &parent, &leaf);
    if (status == 0) {
        struct stat st;
        if (fstatat(parent, leaf, &st, AT_SYMLINK_NOFOLLOW) != 0) {
            status = errno_to_wasi(errno);
        } else {
            uint64_t bytes = (S_ISREG(st.st_mode) || S_ISLNK(st.st_mode)) && st.st_size > 0
                ? (uint64_t) st.st_size : 0;
            int rc = unlinkat(parent, leaf, 0); int e = errno;
            if (rc == 0) quota_remove(state, bytes, 1);
            status = rc == 0 ? 0 : errno_to_wasi(e);
        }
        close(parent); free(path);
    }
    result_errno(results, status); return NULL;
}

static wasm_trap_t *wasi_path_rename(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    if (state == NULL || state->read_only || args[0].of.i32 != WASI_PREOPEN_FD || args[3].of.i32 != WASI_PREOPEN_FD) {
        result_errno(results, state != NULL && state->read_only ? WASI_ENOTCAPABLE : WASI_EBADF); return NULL;
    }
    wasi_memory_view_t memory; if (!memory_view(caller, &memory)) { result_errno(results, WASI_EFAULT); return NULL; }
    char *old_path = NULL, *old_leaf = NULL, *new_path = NULL, *new_leaf = NULL;
    int old_parent = -1, new_parent = -1;
    uint16_t status = path_parent_and_leaf(state, &memory, (uint32_t) args[1].of.i32, (uint32_t) args[2].of.i32, &old_path, &old_parent, &old_leaf);
    if (status == 0) status = path_parent_and_leaf(state, &memory, (uint32_t) args[4].of.i32, (uint32_t) args[5].of.i32, &new_path, &new_parent, &new_leaf);
    if (status == 0) {
        struct stat source = {0}, target = {0};
        if (fstatat(old_parent, old_leaf, &source, AT_SYMLINK_NOFOLLOW) != 0) {
            status = errno_to_wasi(errno);
        } else {
            int target_exists = fstatat(new_parent, new_leaf, &target, AT_SYMLINK_NOFOLLOW) == 0;
            if (!target_exists && errno != ENOENT) {
                status = errno_to_wasi(errno);
            } else if (target_exists && !S_ISREG(target.st_mode) && !S_ISDIR(target.st_mode) && !S_ISLNK(target.st_mode)) {
                status = WASI_ENOTCAPABLE;
            } else {
                int rc = renameat(old_parent, old_leaf, new_parent, new_leaf); int e = errno;
                if (rc == 0 && target_exists
                    && !(source.st_dev == target.st_dev && source.st_ino == target.st_ino)) {
                    uint64_t bytes = (S_ISREG(target.st_mode) || S_ISLNK(target.st_mode)) && target.st_size > 0
                        ? (uint64_t) target.st_size : 0;
                    quota_remove(state, bytes, 1);
                }
                status = rc == 0 ? 0 : errno_to_wasi(e);
            }
        }
    }
    if (old_parent >= 0) close(old_parent);
    if (new_parent >= 0) close(new_parent);
    free(old_path); free(new_path);
    result_errno(results, status); return NULL;
}

static int safe_relative_text(const char *path) {
    if (path == NULL || path[0] == '\0' || path[0] == '/') return 0;
    char *copy = strdup(path); if (copy == NULL) return 0;
    char *save = NULL; int ok = 1;
    for (char *part = strtok_r(copy, "/", &save); part != NULL; part = strtok_r(NULL, "/", &save)) {
        if (!path_component_safe(part)) { ok = 0; break; }
    }
    free(copy); return ok;
}

static wasm_trap_t *wasi_path_symlink(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    if (state == NULL || state->read_only || args[2].of.i32 != WASI_PREOPEN_FD) {
        result_errno(results, state != NULL && state->read_only ? WASI_ENOTCAPABLE : WASI_EBADF); return NULL;
    }
    wasi_memory_view_t memory; if (!memory_view(caller, &memory)) { result_errno(results, WASI_EFAULT); return NULL; }
    char *target = NULL;
    uint16_t status = copy_guest_path(&memory, (uint32_t) args[0].of.i32, (uint32_t) args[1].of.i32, &target);
    if (status != 0 || !safe_relative_text(target)) { free(target); result_errno(results, status == 0 ? WASI_ENOTCAPABLE : status); return NULL; }
    uint64_t target_bytes = strlen(target);
    if (!quota_can_add(state, target_bytes, 1)) {
        free(target); result_errno(results, WASI_ENOSPC); return NULL;
    }
    char *path = NULL, *leaf = NULL; int parent = -1;
    status = path_parent_and_leaf(state, &memory, (uint32_t) args[3].of.i32, (uint32_t) args[4].of.i32, &path, &parent, &leaf);
    if (status == 0) {
        int rc = symlinkat(target, parent, leaf); int e = errno;
        if (rc == 0) quota_add(state, target_bytes, 1);
        status = rc == 0 ? 0 : errno_to_wasi(e);
    }
    if (parent >= 0) close(parent);
    free(target);
    free(path);
    result_errno(results, status); return NULL;
}

static wasm_trap_t *wasi_path_readlink(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    if (state == NULL || args[0].of.i32 != WASI_PREOPEN_FD) { result_errno(results, WASI_EBADF); return NULL; }
    wasi_memory_view_t memory; if (!memory_view(caller, &memory)) { result_errno(results, WASI_EFAULT); return NULL; }
    char *path = NULL, *leaf = NULL; int parent = -1;
    uint16_t status = path_parent_and_leaf(state, &memory, (uint32_t) args[1].of.i32, (uint32_t) args[2].of.i32, &path, &parent, &leaf);
    uint32_t buffer_ptr = (uint32_t) args[3].of.i32, buffer_len = (uint32_t) args[4].of.i32;
    if (state->max_host_call_bytes > 0 && buffer_len > state->max_host_call_bytes) {
        if (parent >= 0) close(parent);
        free(path);
        result_errno(results, WASI_E2BIG);
        return NULL;
    }
    uint8_t *buffer = NULL, *used = NULL;
    if (status == 0 && (!memory_range(&memory, buffer_ptr, buffer_len, &buffer) || !memory_range(&memory, (uint32_t) args[5].of.i32, 4, &used))) status = WASI_EFAULT;
    if (status == 0) {
        ssize_t count = readlinkat(parent, leaf, (char *) buffer, buffer_len); int e = errno;
        if (count < 0) status = errno_to_wasi(e); else store_u32(used, (uint32_t) count);
    }
    if (parent >= 0) close(parent);
    free(path);
    result_errno(results, status); return NULL;
}

static wasm_trap_t *wasi_fd_readdir(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    wasi_lite_fd_t *entry = lookup_fd(state, args[0].of.i32);
    if (entry == NULL || !entry->is_dir) { result_errno(results, WASI_EBADF); return NULL; }
    wasi_memory_view_t memory; uint8_t *buffer = NULL, *used_ptr = NULL;
    uint32_t buffer_offset = (uint32_t) args[1].of.i32;
    uint32_t buffer_len = (uint32_t) args[2].of.i32;
    uint64_t cookie = (uint64_t) args[3].of.i64;
    if (state->max_host_call_bytes > 0 && buffer_len > state->max_host_call_bytes) {
        result_errno(results, WASI_E2BIG); return NULL;
    }
    if (!memory_view(caller, &memory) || !memory_range(&memory, buffer_offset, buffer_len, &buffer)
        || !memory_range(&memory, (uint32_t) args[4].of.i32, 4, &used_ptr)) {
        result_errno(results, WASI_EFAULT); return NULL;
    }
    int duplicate = dup(entry->host_fd);
    if (duplicate < 0) { result_errno(results, errno_to_wasi(errno)); return NULL; }
    DIR *dir = fdopendir(duplicate);
    if (dir == NULL) { close(duplicate); result_errno(results, errno_to_wasi(errno)); return NULL; }
    uint64_t index = 0; uint32_t written = 0; struct dirent *item;
    while ((item = readdir(dir)) != NULL) {
        if (index++ < cookie) continue;
        size_t name_len = strlen(item->d_name);
        uint8_t header[24] = {0};
        store_u64(header + 0, index);
        store_u64(header + 8, (uint64_t) item->d_ino);
        store_u32(header + 16, (uint32_t) name_len);
#ifdef _WIN32
        struct stat entry_stat;
        if (fstatat(entry->host_fd, item->d_name, &entry_stat, AT_SYMLINK_NOFOLLOW) == 0) {
            header[20] = wasi_filetype(entry_stat.st_mode);
        } else {
            header[20] = WASI_FILETYPE_UNKNOWN;
        }
#else
        switch (item->d_type) {
            case DT_DIR: header[20] = WASI_FILETYPE_DIRECTORY; break;
            case DT_REG: header[20] = WASI_FILETYPE_REGULAR_FILE; break;
            case DT_LNK: header[20] = WASI_FILETYPE_SYMBOLIC_LINK; break;
            default: header[20] = WASI_FILETYPE_UNKNOWN; break;
        }
#endif
        size_t remaining = buffer_len - written;
        size_t header_copy = remaining < sizeof(header) ? remaining : sizeof(header);
        memcpy(buffer + written, header, header_copy); written += (uint32_t) header_copy;
        if (header_copy < sizeof(header)) break;
        remaining = buffer_len - written;
        size_t name_copy = remaining < name_len ? remaining : name_len;
        memcpy(buffer + written, item->d_name, name_copy); written += (uint32_t) name_copy;
        if (name_copy < name_len || written == buffer_len) break;
    }
    closedir(dir);
    store_u32(used_ptr, written);
    result_errno(results, WASI_ESUCCESS);
    return NULL;
}

static uint64_t clock_now_ns(uint32_t clock_id, int *ok) {
    clockid_t id;
    switch (clock_id) {
        case 0: id = CLOCK_REALTIME; break;
        case 1: id = CLOCK_MONOTONIC; break;
        default: *ok = 0; return 0;
    }
    struct timespec ts;
    if (clock_gettime(id, &ts) != 0) { *ok = 0; return 0; }
    *ok = 1;
    return stat_time_ns(ts.tv_sec, ts.tv_nsec);
}

static wasm_trap_t *wasi_poll_oneoff(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) nargs; (void) nresults;
    wasmtime_kmp_wasi_lite_t *state = env;
    wasi_memory_view_t memory;
    uint32_t subscriptions = (uint32_t) args[0].of.i32;
    uint32_t events = (uint32_t) args[1].of.i32;
    uint32_t count = (uint32_t) args[2].of.i32;
    uint32_t nevents_ptr = (uint32_t) args[3].of.i32;
    uint8_t *subs = NULL, *event = NULL, *nevents = NULL;
    if (count == 0) { result_errno(results, WASI_EINVAL); return NULL; }
    if (count > WASI_MAX_SUBSCRIPTIONS) { result_errno(results, WASI_E2BIG); return NULL; }
    if (!memory_view(caller, &memory)
        || !memory_range(&memory, subscriptions, (size_t) count * WASI_SUBSCRIPTION_SIZE, &subs)
        || !memory_range(&memory, events, WASI_EVENT_SIZE, &event)
        || !memory_range(&memory, nevents_ptr, 4, &nevents)) {
        result_errno(results, WASI_EFAULT); return NULL;
    }
    uint64_t best_delay = UINT64_MAX; uint64_t best_userdata = 0; int found = 0;
    for (uint32_t index = 0; index < count; index++) {
        uint8_t *sub = subs + index * WASI_SUBSCRIPTION_SIZE;
        if (sub[8] != WASI_EVENTTYPE_CLOCK) continue;
        uint32_t clock_id = load_u32(sub + 16);
        uint64_t timeout = load_u64(sub + 24);
        uint16_t flags = load_u16(sub + 40);
        uint64_t delay = timeout;
        if (flags & WASI_SUBCLOCK_ABSTIME) {
            int ok = 0; uint64_t now = clock_now_ns(clock_id, &ok);
            if (!ok) continue;
            delay = timeout > now ? timeout - now : 0;
        }
        if (!found || delay < best_delay) {
            found = 1; best_delay = delay; best_userdata = load_u64(sub);
        }
    }
    if (!found) { result_errno(results, WASI_ENOSYS); return NULL; }
    if (state != NULL && state->max_wasi_poll_millis > 0) {
        uint64_t max_ns = state->max_wasi_poll_millis > UINT64_MAX / UINT64_C(1000000)
            ? UINT64_MAX : state->max_wasi_poll_millis * UINT64_C(1000000);
        if (best_delay > max_ns) { result_errno(results, WASI_ENOTCAPABLE); return NULL; }
    }
    if (best_delay > 0) {
        struct timespec wait = {
            .tv_sec = (time_t) (best_delay / UINT64_C(1000000000)),
            .tv_nsec = (long) (best_delay % UINT64_C(1000000000)),
        };
        while (nanosleep(&wait, &wait) != 0 && errno == EINTR) {}
    }
    memset(event, 0, WASI_EVENT_SIZE);
    store_u64(event, best_userdata);
    store_u16(event + 8, WASI_ESUCCESS);
    event[10] = WASI_EVENTTYPE_CLOCK;
    store_u32(nevents, 1);
    result_errno(results, WASI_ESUCCESS);
    return NULL;
}

static wasm_trap_t *wasi_proc_exit(
    void *env, wasmtime_caller_t *caller, const wasmtime_val_t *args, size_t nargs,
    wasmtime_val_t *results, size_t nresults
) {
    (void) env; (void) caller; (void) nargs; (void) results; (void) nresults;
    char message[64];
    int length = snprintf(message, sizeof(message), "WASI proc_exit(%d)", args[0].of.i32);
    return wasmtime_trap_new(message, (size_t) (length > 0 ? length : 0));
}

static wasm_functype_t *function_type(const wasm_valkind_t *params, size_t param_count, int has_result) {
    wasm_valtype_vec_t param_vec;
    wasm_valtype_vec_new_uninitialized(&param_vec, param_count);
    for (size_t i = 0; i < param_count; i++) param_vec.data[i] = wasm_valtype_new(params[i]);
    wasm_valtype_vec_t result_vec;
    if (has_result) {
        wasm_valtype_vec_new_uninitialized(&result_vec, 1);
        result_vec.data[0] = wasm_valtype_new_i32();
    } else {
        wasm_valtype_vec_new_empty(&result_vec);
    }
    return wasm_functype_new(&param_vec, &result_vec);
}

static wasmtime_error_t *define_import(
    wasmtime_linker_t *linker,
    wasmtime_kmp_wasi_lite_t *state,
    const char *name,
    const wasm_valkind_t *params,
    size_t param_count,
    int has_result,
    wasmtime_func_callback_t callback
) {
    wasm_functype_t *type = function_type(params, param_count, has_result);
    if (type == NULL) return NULL;
    wasmtime_error_t *error = wasmtime_linker_define_func(
        linker,
        "wasi_snapshot_preview1", strlen("wasi_snapshot_preview1"),
        name, strlen(name),
        type, callback, state, NULL
    );
    wasm_functype_delete(type);
    return error;
}

#define DEFINE(name, callback, has_result, ...) do { \
    const wasm_valkind_t kinds[] = { __VA_ARGS__ }; \
    error = define_import(linker, state, name, kinds, ARRAY_LEN(kinds), has_result, callback); \
    if (error != NULL) return error; \
} while (0)

wasmtime_error_t *wasmtime_kmp_wasi_lite_define(
    wasmtime_linker_t *linker,
    wasmtime_kmp_wasi_lite_t *state
) {
    wasmtime_error_t *error = NULL;
    DEFINE("clock_time_get", wasi_clock_time_get, 1, WASM_I32, WASM_I64, WASM_I32);
    DEFINE("fd_close", wasi_fd_close, 1, WASM_I32);
    DEFINE("fd_prestat_dir_name", wasi_fd_prestat_dir_name, 1, WASM_I32, WASM_I32, WASM_I32);
    DEFINE("fd_prestat_get", wasi_fd_prestat_get, 1, WASM_I32, WASM_I32);
    DEFINE("fd_read", wasi_fd_read, 1, WASM_I32, WASM_I32, WASM_I32, WASM_I32);
    DEFINE("fd_readdir", wasi_fd_readdir, 1, WASM_I32, WASM_I32, WASM_I32, WASM_I64, WASM_I32);
    DEFINE("fd_sync", wasi_fd_sync, 1, WASM_I32);
    DEFINE("fd_write", wasi_fd_write, 1, WASM_I32, WASM_I32, WASM_I32, WASM_I32);
    DEFINE("path_create_directory", wasi_path_create_directory, 1, WASM_I32, WASM_I32, WASM_I32);
    DEFINE("path_filestat_get", wasi_path_filestat_get, 1, WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I32);
    DEFINE("path_open", wasi_path_open, 1, WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I64, WASM_I64, WASM_I32, WASM_I32);
    DEFINE("path_readlink", wasi_path_readlink, 1, WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I32);
    DEFINE("path_remove_directory", wasi_path_remove_directory, 1, WASM_I32, WASM_I32, WASM_I32);
    DEFINE("path_rename", wasi_path_rename, 1, WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I32);
    DEFINE("path_symlink", wasi_path_symlink, 1, WASM_I32, WASM_I32, WASM_I32, WASM_I32, WASM_I32);
    DEFINE("path_unlink_file", wasi_path_unlink_file, 1, WASM_I32, WASM_I32, WASM_I32);
    DEFINE("poll_oneoff", wasi_poll_oneoff, 1, WASM_I32, WASM_I32, WASM_I32, WASM_I32);
    DEFINE("proc_exit", wasi_proc_exit, 0, WASM_I32);
    DEFINE("random_get", wasi_random_get, 1, WASM_I32, WASM_I32);
    return NULL;
}

#undef DEFINE

wasmtime_kmp_wasi_lite_t *wasmtime_kmp_wasi_lite_new(
    const wasmtime_kmp_storage_t *storage,
    const wasmtime_kmp_limits_t *limits,
    char **error_out
) {
    wasmtime_kmp_wasi_lite_t *state = calloc(1, sizeof(*state));
    if (state == NULL) {
        if (error_out != NULL) *error_out = strdup("out of memory creating lightweight WASI state");
        return NULL;
    }
    state->root_fd = -1;
    for (int i = 0; i < WASI_MAX_FDS; i++) state->fds[i].host_fd = -1;
    if (limits != NULL) {
        state->max_host_call_bytes = limits->max_host_call_bytes > 0
            ? (uint64_t) limits->max_host_call_bytes : 0;
        state->max_output_bytes = limits->max_output_bytes > 0
            ? (uint64_t) limits->max_output_bytes : 0;
        state->max_wasi_poll_millis = limits->max_wasi_poll_millis > 0
            ? (uint64_t) limits->max_wasi_poll_millis : 0;
        if (limits->max_execution_millis > 0
            && (state->max_wasi_poll_millis == 0
                || state->max_wasi_poll_millis > (uint64_t) limits->max_execution_millis)) {
            state->max_wasi_poll_millis = (uint64_t) limits->max_execution_millis;
        }
    }
    if (storage == NULL) return state;
    if (storage->backing_path == NULL || storage->guest_path == NULL) {
        if (error_out != NULL) *error_out = strdup("storage paths must not be null");
        free(state);
        return NULL;
    }
    state->guest_path = strdup(storage->guest_path);
    state->read_only = storage->read_only ? 1 : 0;
    state->storage_max_bytes = storage->max_bytes > 0 ? (uint64_t) storage->max_bytes : 0;
    state->storage_max_entries = storage->max_entries > 0 ? (uint64_t) storage->max_entries : 0;
    state->storage_max_file_bytes = storage->max_file_bytes > 0 ? (uint64_t) storage->max_file_bytes : 0;
    if (state->guest_path == NULL) {
        if (error_out != NULL) *error_out = strdup("out of memory copying guest storage path");
        free(state);
        return NULL;
    }
    state->root_fd = open(storage->backing_path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (state->root_fd < 0) {
        if (error_out != NULL) {
            const char *prefix = "failed to open sandbox storage directory: ";
            const char *reason = strerror(errno);
            size_t size = strlen(prefix) + strlen(reason) + 1;
            *error_out = malloc(size);
            if (*error_out != NULL) snprintf(*error_out, size, "%s%s", prefix, reason);
        }
        free(state->guest_path);
        free(state);
        return NULL;
    }
    /* Quotas are tracked per mounted sandbox. Hold an advisory exclusive lock
     * for the lifetime of the instance so two native processes/instances cannot
     * race the same backing directory past its configured quota. */
    int storage_lock = state->read_only ? LOCK_SH : LOCK_EX;
    if (flock(state->root_fd, storage_lock | LOCK_NB) != 0) {
        if (error_out != NULL) *error_out = strdup("sandbox storage is already in use");
        close(state->root_fd);
        free(state->guest_path);
        free(state);
        return NULL;
    }

    uint64_t used_bytes = 0;
    uint64_t used_entries = 0;
    if (!scan_storage_directory(
            state->root_fd,
            &used_bytes,
            &used_entries,
            state->storage_max_bytes,
            state->storage_max_entries,
            state->storage_max_file_bytes,
            0
        )) {
        if (error_out != NULL) {
            *error_out = strdup("sandbox storage contains an unsupported object, excessive depth, or oversized file");
        }
        close(state->root_fd);
        free(state->guest_path);
        free(state);
        return NULL;
    }
    if ((state->storage_max_bytes > 0 && used_bytes > state->storage_max_bytes)
        || (state->storage_max_entries > 0 && used_entries > state->storage_max_entries)) {
        if (error_out != NULL) *error_out = strdup("sandbox storage already exceeds configured quota");
        close(state->root_fd);
        free(state->guest_path);
        free(state);
        return NULL;
    }
    state->storage_used_bytes = used_bytes;
    state->storage_entries = used_entries;
    return state;
}

void wasmtime_kmp_wasi_lite_delete(wasmtime_kmp_wasi_lite_t *state) {
    if (state == NULL) return;
    for (int fd = WASI_FIRST_DYNAMIC_FD; fd < WASI_MAX_FDS; fd++) {
        if (state->fds[fd].used && state->fds[fd].host_fd >= 0) close(state->fds[fd].host_fd);
    }
    if (state->root_fd >= 0) close(state->root_fd);
    free(state->guest_path);
    free(state);
}
