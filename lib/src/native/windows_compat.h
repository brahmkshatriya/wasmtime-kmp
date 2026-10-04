#ifndef WASMTIME_KMP_WINDOWS_COMPAT_H
#define WASMTIME_KMP_WINDOWS_COMPAT_H

#ifdef _WIN32

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/stat.h>
#include <dirent.h>
#include <fcntl.h>
#include <io.h>

#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif
#ifndef O_NOFOLLOW
#define O_NOFOLLOW 0x10000000
#endif
#ifndef O_DIRECTORY
#define O_DIRECTORY 0x20000000
#endif
#ifndef AT_SYMLINK_NOFOLLOW
#define AT_SYMLINK_NOFOLLOW 0x100
#endif
#ifndef AT_REMOVEDIR
#define AT_REMOVEDIR 0x200
#endif
#ifndef S_IFLNK
#define S_IFLNK 0120000
#endif
#ifndef S_ISLNK
#define S_ISLNK(mode) (((mode) & S_IFMT) == S_IFLNK)
#endif
#ifndef F_GETFL
#define F_GETFL 3
#endif
#ifndef LOCK_SH
#define LOCK_SH 1
#define LOCK_EX 2
#define LOCK_NB 4
#define LOCK_UN 8
#endif

int wkm_open(const char *path, int flags, ...);
int wkm_close(int fd);
int wkm_openat(int parent_fd, const char *path, int flags, ...);
int wkm_fstatat(int parent_fd, const char *path, struct stat *st, int flags);
DIR *wkm_fdopendir(int fd);
int wkm_mkdir(const char *path, int mode);
int wkm_mkdirat(int parent_fd, const char *path, int mode);
int wkm_unlinkat(int parent_fd, const char *path, int flags);
int wkm_renameat(int old_parent_fd, const char *old_path, int new_parent_fd, const char *new_path);
int wkm_symlinkat(const char *target, int parent_fd, const char *path);
ssize_t wkm_readlinkat(int parent_fd, const char *path, char *buffer, size_t size);
int wkm_flock(int fd, int operation);
int wkm_fcntl(int fd, int command, ...);
int wkm_fsync(int fd);
int wkm_fchmod(int fd, int mode);
int wkm_lstat(const char *path, struct stat *st);
unsigned wkm_geteuid(void);
char *wkm_realpath(const char *path, char *resolved);
int wkm_mkstemp(char *template_path);

#define open wkm_open
#define close wkm_close
#define openat wkm_openat
#define fstatat wkm_fstatat
#define fdopendir wkm_fdopendir
#define mkdir(path, mode) wkm_mkdir((path), (mode))
#define mkdirat wkm_mkdirat
#define unlinkat wkm_unlinkat
#define renameat wkm_renameat
#define symlinkat wkm_symlinkat
#define readlinkat wkm_readlinkat
#define flock wkm_flock
#define fcntl wkm_fcntl
#define fsync wkm_fsync
#define fchmod wkm_fchmod
#define lstat wkm_lstat
#define geteuid wkm_geteuid
#define realpath wkm_realpath
#define mkstemp wkm_mkstemp

#endif
#endif
