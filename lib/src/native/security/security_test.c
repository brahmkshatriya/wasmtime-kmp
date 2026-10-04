#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "wasmtime_kmp.h"

typedef struct { uint8_t *data; size_t len; } bytes_t;
static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { fprintf(stderr, "FAIL|%s\n", msg); failures++; } else { printf("PASS|%s\n", msg); } } while (0)

static bytes_t read_file(const char *path) {
    FILE *f=fopen(path,"rb"); if(!f){perror(path); exit(2);} fseek(f,0,SEEK_END); long n=ftell(f); rewind(f);
    uint8_t *p=malloc((size_t)n); if(n && fread(p,1,(size_t)n,f)!=(size_t)n){perror("fread");exit(2);} fclose(f);
    return (bytes_t){p,(size_t)n};
}
static char *join(const char *dir,const char *name){size_t n=strlen(dir)+strlen(name)+2;char *p=malloc(n);snprintf(p,n,"%s/%s",dir,name);return p;}
static int process_threads(void) {
    FILE *f=fopen("/proc/self/status","r"); if(!f)return -1; char line[256]; int threads=-1;
    while(fgets(line,sizeof(line),f)){if(sscanf(line,"Threads: %d",&threads)==1)break;} fclose(f); return threads;
}
static wasmtime_kmp_limits_t limits_default(void) {
    wasmtime_kmp_limits_t l={0};
    l.max_memory_bytes=64LL*1024*1024; l.fuel=100000000; l.max_table_elements=1000000;
    l.max_host_call_bytes=1024*1024; l.max_output_bytes=1024*1024; l.max_http_response_bytes=16*1024*1024; l.max_wasi_poll_millis=1000;
    return l;
}
static wasmtime_kmp_storage_t storage_at(const char *path) {
    wasmtime_kmp_storage_t s={0}; s.backing_path=path; s.guest_path="/data"; s.max_bytes=64LL*1024*1024; s.max_entries=4096; s.max_file_bytes=16LL*1024*1024; return s;
}
static wasmtime_kmp_module_t *compile_path(const char *dir,const char *name){
    char *p=join(dir,name); bytes_t b=read_file(p); free(p); char *err=NULL;
    wasmtime_kmp_module_t *m=wasmtime_kmp_compile(b.data,b.len,&err); free(b.data);
    if(!m){fprintf(stderr,"compile %s: %s\n",name,err?err:"?");wasmtime_kmp_string_free(err); failures++;}
    return m;
}
static int run_code(wasmtime_kmp_module_t *m, wasmtime_kmp_limits_t *l, wasmtime_kmp_storage_t *s, int *ok_out){
    char *err=NULL; wasmtime_kmp_instance_t *i=wasmtime_kmp_instantiate_with_capabilities(m,l,NULL,s,&err);
    if(!i){fprintf(stderr,"instantiate: %s\n",err?err:"?");wasmtime_kmp_string_free(err);*ok_out=0;return -999;}
    wasmtime_kmp_func_i32_2_t *f=wasmtime_kmp_resolve_i32_2(i,"run",&err);
    if(!f){fprintf(stderr,"resolve: %s\n",err?err:"?");wasmtime_kmp_string_free(err);wasmtime_kmp_close(i);*ok_out=0;return -999;}
    int32_t result=0; int ok=wasmtime_kmp_func_i32_2_call(f,0,0,&result,&err);
    if(!ok){fprintf(stderr,"call: %s\n",err?err:"?");wasmtime_kmp_string_free(err);}
    wasmtime_kmp_func_i32_2_close(f); wasmtime_kmp_close(i); *ok_out=ok; return result;
}

typedef struct { wasmtime_kmp_func_i32_2_t *f; atomic_int started; int calls; int saw_closed; } race_t;
static void *race_worker(void *arg){ race_t *r=arg; atomic_store(&r->started,1); for(int k=0;k<1000000;k++){
    int32_t v=0; char *err=NULL; int ok=wasmtime_kmp_func_i32_2_call(r->f,k,1,&v,&err); if(!ok){r->saw_closed=1;wasmtime_kmp_string_free(err);break;} if(v!=k+1){failures++;break;} r->calls++; } return NULL; }

int main(int argc,char **argv){
    if(argc!=2){fprintf(stderr,"usage: %s wasm-dir\n",argv[0]);return 2;} const char *dir=argv[1];
    char root_template[]="/tmp/wasmtime-sec-root-XXXXXX"; char *root=mkdtemp(root_template); if(!root){perror("mkdtemp");return 2;}
    wasmtime_kmp_limits_t l=limits_default(); wasmtime_kmp_storage_t st=storage_at(root); int ok=0, result=0;

    wasmtime_kmp_module_t *m=compile_path(dir,"path_traversal.wasm"); if(m){result=run_code(m,&l,&st,&ok); CHECK(ok && result==76,"path traversal rejected"); wasmtime_kmp_module_close(m);}
    m=compile_path(dir,"path_trailing_nul.wasm"); if(m){result=run_code(m,&l,&st,&ok); CHECK(ok && result==0,"single trailing NUL in path length accepted"); wasmtime_kmp_module_close(m);}

    char *linkpath=join(root,"link"); symlink("/etc/passwd",linkpath); free(linkpath);
    m=compile_path(dir,"path_symlink.wasm"); if(m){result=run_code(m,&l,&st,&ok); CHECK(ok && result==76,"symlink leaf escape rejected"); wasmtime_kmp_module_close(m);}

    m=compile_path(dir,"poll_too_long.wasm"); if(m){result=run_code(m,&l,NULL,&ok); CHECK(ok && result==76,"overlong poll_oneoff rejected"); wasmtime_kmp_module_close(m);}
    m=compile_path(dir,"host_call_too_large.wasm"); if(m){result=run_code(m,&l,NULL,&ok); CHECK(ok && result==1,"oversized host call rejected"); wasmtime_kmp_module_close(m);}

    m=compile_path(dir,"import_only.wasm"); if(m){
        char *err1=NULL,*err2=NULL;
        wasmtime_kmp_instance_t *first=wasmtime_kmp_instantiate_with_capabilities(m,&l,NULL,&st,&err1);
        wasmtime_kmp_instance_t *second=wasmtime_kmp_instantiate_with_capabilities(m,&l,NULL,&st,&err2);
        CHECK(first!=NULL && second==NULL,"same storage cannot be mounted concurrently");
        if(second)wasmtime_kmp_close(second);
        if(first)wasmtime_kmp_close(first);
        wasmtime_kmp_string_free(err1);wasmtime_kmp_string_free(err2);wasmtime_kmp_module_close(m);
    }

    char quota_template[]="/tmp/wasmtime-sec-quota-XXXXXX"; char *quota=mkdtemp(quota_template); wasmtime_kmp_storage_t qs=storage_at(quota); qs.max_bytes=1024; qs.max_file_bytes=4096;
    m=compile_path(dir,"storage_write_quota.wasm"); if(m){result=run_code(m,&l,&qs,&ok); CHECK(ok && result==51,"storage write quota enforced"); wasmtime_kmp_module_close(m);}

    char fullpath[512]; snprintf(fullpath,sizeof(fullpath),"%s/existing",quota); FILE *qf=fopen(fullpath,"wb"); char block[2048]={0}; fwrite(block,1,sizeof(block),qf); fclose(qf);
    m=compile_path(dir,"import_only.wasm"); if(m){char *err=NULL; wasmtime_kmp_instance_t *i=wasmtime_kmp_instantiate_with_capabilities(m,&l,NULL,&qs,&err); CHECK(i==NULL,"pre-existing over-quota storage rejected"); if(i)wasmtime_kmp_close(i); wasmtime_kmp_string_free(err); wasmtime_kmp_module_close(m);}

    m=compile_path(dir,"table_100.wasm"); if(m){wasmtime_kmp_limits_t tl=l; tl.max_table_elements=10; char *err=NULL; wasmtime_kmp_instance_t *i=wasmtime_kmp_instantiate(m,&tl,&err); CHECK(i==NULL,"table element limit enforced"); if(i)wasmtime_kmp_close(i); wasmtime_kmp_string_free(err);wasmtime_kmp_module_close(m);}
    m=compile_path(dir,"memory_2_pages.wasm"); if(m){wasmtime_kmp_limits_t ml=l; ml.max_memory_bytes=65536; char *err=NULL; wasmtime_kmp_instance_t *i=wasmtime_kmp_instantiate(m,&ml,&err); CHECK(i==NULL,"linear memory limit enforced"); if(i)wasmtime_kmp_close(i); wasmtime_kmp_string_free(err);wasmtime_kmp_module_close(m);}

    m=compile_path(dir,"add.wasm"); if(m){char *err=NULL; wasmtime_kmp_instance_t *i=wasmtime_kmp_instantiate(m,&l,&err); CHECK(i!=NULL,"lifetime race instance created"); if(i){wasmtime_kmp_func_i32_2_t *f=wasmtime_kmp_resolve_i32_2(i,"add",&err); CHECK(f!=NULL,"lifetime race function resolved"); if(f){race_t r={.f=f};atomic_init(&r.started,0);pthread_t th;pthread_create(&th,NULL,race_worker,&r);while(!atomic_load(&r.started)){} wasmtime_kmp_close(i); pthread_join(th,NULL); CHECK(r.saw_closed || r.calls==1000000,"close-vs-call completed safely"); wasmtime_kmp_func_i32_2_close(f);} else wasmtime_kmp_close(i);} wasmtime_kmp_string_free(err);wasmtime_kmp_module_close(m);}

    // Basic repeated lifecycle leak/crash regression.
    m=compile_path(dir,"add.wasm"); if(m){int good=1; for(int k=0;k<5000;k++){char *err=NULL;wasmtime_kmp_instance_t *i=wasmtime_kmp_instantiate(m,&l,&err);if(!i){good=0;wasmtime_kmp_string_free(err);break;}wasmtime_kmp_close(i);}CHECK(good,"5000 instantiate/close cycles");wasmtime_kmp_module_close(m);}

    // Timed instances create the shared epoch ticker; repeated teardown must not leak threads.
    m=compile_path(dir,"add.wasm"); if(m){
        int before=process_threads(), good=1; wasmtime_kmp_limits_t timed=l; timed.max_execution_millis=50;
        for(int k=0;k<1000;k++){char *err=NULL;wasmtime_kmp_instance_t *i=wasmtime_kmp_instantiate(m,&timed,&err);if(!i){good=0;wasmtime_kmp_string_free(err);break;}wasmtime_kmp_close(i);}
        int after=process_threads();
        CHECK(good && (before<0 || after<=before+1),"1000 timed instance cycles release epoch threads");
        wasmtime_kmp_module_close(m);
    }

    printf("SUMMARY|failures=%d\n",failures); return failures?1:0;
}
