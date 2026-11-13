/* Module metadata, enable/disable controls and reboot-staged update commit.
 * customize.sh is handled by the APP installer before this code queues the module.
 * No mounting or policy loader is provided here; this is not a root-script sandbox. */
static int str_eq(const char *,const char *);
static int mod_id(const char *s) {
    int n=0; if(!s || !*s)return 0;
    for(;*s;s++,n++)if(n>=63 || !((*s>='a'&&*s<='z')||(*s>='A'&&*s<='Z')||(*s>='0'&&*s<='9')||*s=='_'||*s=='-'||*s=='.'))return 0;
    return n && s[-1]!='.';
}
static int mod_read(const char *path,char *b,int cap) {
    long f=sc3(SYS_openat,AT_FDCWD,(i64)path,O_RDONLY); if(f<0)return -1;
    long n=sc3(SYS_read,f,(i64)b,cap-1);sc1(SYS_close,f);if(n<0)return -1;b[n]=0;return n;
}
static int mod_exists(const char *path) {
    long f=sc3(SYS_openat,AT_FDCWD,(i64)path,O_RDONLY);if(f<0)return 0;sc1(SYS_close,f);return 1;
}
static void mod_path(char *out,const char *id,const char *tail) {
    char *p=stpcpy_(out,"/data/adb/modules/");p=stpcpy_(p,id);stpcpy_(p,tail);
}
static int mod_mark_pending(const char *id) {
    char boot[80],path[256];int n=mod_read("/proc/sys/kernel/random/boot_id",boot,sizeof(boot));if(n<=0)return -1;
    mod_path(path,id,"/.cairn_pending");long f=sc4(SYS_openat,AT_FDCWD,(i64)path,O_WRONLY|O_CREAT|O_TRUNC,0600);if(f<0)return -1;
    long rc=sc3(SYS_write,f,(i64)boot,n);sc1(SYS_close,f);return rc==n?0:-1;
}
/* Same-boot installed/enabled modules may not execute, even via manual --stage. */
static int mod_pending(const char *id) {
    char path[256],old[80],now[80];mod_path(path,id,"/.cairn_pending");
    if(!mod_exists(path))return 0;
    if(mod_read(path,old,sizeof(old))<=0 || mod_read("/proc/sys/kernel/random/boot_id",now,sizeof(now))<=0)return 1;
    return str_eq(old,now);
}
static void mod_out(const char *s){sc3(SYS_write,1,(i64)s,slen(s));}
static int modules_cmd(long argc,char **argv) {
    if(sc0(SYS_geteuid)!=0)return 1;
    if(argc<3)return 2;
    const char *op=argv[2];
    if(str_eq(op,"list")) {
        long f=sc3(SYS_openat,AT_FDCWD,(i64)"/data/adb/modules",O_RDONLY|O_DIRECTORY);if(f<0)return 0;
        union {long align;char raw[4096];} u;long n;
        while((n=sc3(SYS_getdents64,f,(i64)u.raw,sizeof(u.raw)))>0)for(long o=0;o<n;){
            struct dirent64_ *d=(struct dirent64_ *)(u.raw+o);if(!d->d_reclen)break;o+=d->d_reclen;
            const char *id=d->d_name;if(id[0]=='.'||!mod_id(id))continue;
            char p[256];mod_path(p,id,"/module.prop");if(!mod_exists(p))continue;
            char up[256];char *t=stpcpy_(up,"/data/adb/modules_update/");stpcpy_(t,id);
            mod_path(p,id,"/disable");const char *state=mod_exists(p)?"disabled":((mod_exists(up)||mod_pending(id))?"pending":"enabled");
            mod_out(id);mod_out("\t");mod_out(state);mod_out("\n");
        }sc1(SYS_close,f);return 0;
    }
    if(argc<4||!mod_id(argv[3])||argv[3][0]=='.')return 2;
    const char *id=argv[3];char p[256];mod_path(p,id,"/module.prop");
    if(str_eq(op,"install")) {
        
        char src[256],dst[256];char *q=stpcpy_(src,"/data/adb/cairn/staging/");stpcpy_(q,id);
        char check[320];q=stpcpy_(check,src);stpcpy_(q,"/module.prop");if(!mod_exists(check))return 4;
        sc3(SYS_mkdirat,AT_FDCWD,(i64)"/data/adb/modules",0700);
        char *d=stpcpy_(dst,"/data/adb/modules_update/");stpcpy_(d,id);
        sc3(SYS_mkdirat,AT_FDCWD,(i64)"/data/adb/modules_update",0700);
        if(mod_exists(dst))return 3;
        /* Mark pending before publish, no executable unmarked window. */
        char boot[80];int n=mod_read("/proc/sys/kernel/random/boot_id",boot,sizeof(boot));if(n<=0)return 5;
        q=stpcpy_(check,src);stpcpy_(q,"/.cairn_pending");long f=sc4(SYS_openat,AT_FDCWD,(i64)check,O_WRONLY|O_CREAT|O_TRUNC,0600);if(f<0)return 5;
        long r=sc3(SYS_write,f,(i64)boot,n);sc1(SYS_close,f);if(r!=n)return 5;
        if(sc4(SYS_renameat,AT_FDCWD,(i64)src,AT_FDCWD,(i64)dst)<0)return 6;
        char active[256];mod_path(active,id,"");
        if(!mod_exists(active)){
            sc3(SYS_mkdirat,AT_FDCWD,(i64)active,0700);
            q=stpcpy_(check,dst);stpcpy_(q,"/module.prop");char prop[4096];int pn=mod_read(check,prop,sizeof(prop));
            mod_path(check,id,"/module.prop");long out=sc4(SYS_openat,AT_FDCWD,(i64)check,O_WRONLY|O_CREAT,0600);
            if(out>=0){if(pn>0)sc3(SYS_write,out,(i64)prop,pn);sc1(SYS_close,out);}
            mod_mark_pending(id);
        }
        return 0;
    }
    if(!mod_exists(p))return 4;
    mod_path(p,id,"/disable");
    if(str_eq(op,"disable")){long f=sc4(SYS_openat,AT_FDCWD,(i64)p,O_WRONLY|O_CREAT,0600);if(f<0)return 5;sc1(SYS_close,f);return 0;}
    if(str_eq(op,"enable")){if(mod_mark_pending(id)<0)return 5;sc3(SYS_unlinkat,AT_FDCWD,(i64)p,0);return mod_exists(p)?5:0;}
    return 2;
}

/* Remove manager-owned rollback tree without following symlinks. */
static int mod_delete(const char *path,int depth){
 if(depth>24)return -1;
 long fd=sc3(SYS_openat,AT_FDCWD,(i64)path,O_RDONLY|O_DIRECTORY|0x20000);
 if(fd<0)return sc3(SYS_unlinkat,AT_FDCWD,(i64)path,0)<0?-1:0;
 union {long align;char raw[4096];} u;long n;int fail=0;
 while((n=sc3(SYS_getdents64,fd,(i64)u.raw,sizeof(u.raw)))>0)for(long o=0;o<n;){
  struct dirent64_ *d=(struct dirent64_ *)(u.raw+o);if(!d->d_reclen){fail=1;break;}o+=d->d_reclen;
  if(str_eq(d->d_name,".")||str_eq(d->d_name,".."))continue;
  if(slen(path)+slen(d->d_name)+2>=1024){fail=1;continue;}
  char p[1024];char *q=stpcpy_(p,path);q=stpcpy_(q,"/");stpcpy_(q,d->d_name);if(mod_delete(p,depth+1)<0)fail=1;
 }
 sc1(SYS_close,fd);if(n<0||fail)return -1;
 return sc3(SYS_unlinkat,AT_FDCWD,(i64)path,0x200)<0?-1:0;
}
/* Commit only on a later boot. Old directory retained as hidden rollback backup. */
static void modules_commit(void){
 long fd=sc3(SYS_openat,AT_FDCWD,(i64)"/data/adb/modules_update",O_RDONLY|O_DIRECTORY);if(fd<0)return;
 union {long align;char raw[4096];} u;long n;
 while((n=sc3(SYS_getdents64,fd,(i64)u.raw,sizeof(u.raw)))>0)for(long o=0;o<n;){
  struct dirent64_ *de=(struct dirent64_ *)(u.raw+o);if(!de->d_reclen)break;o+=de->d_reclen;const char *id=de->d_name;
  if(id[0]=='.'||!mod_id(id))continue;
  char src[256],dst[256],bak[256],flag[300],oldboot[80],boot[80];
  char *q=stpcpy_(src,"/data/adb/modules_update/");stpcpy_(q,id);q=stpcpy_(flag,src);stpcpy_(q,"/.cairn_pending");
  if(mod_read(flag,oldboot,sizeof(oldboot))<=0||mod_read("/proc/sys/kernel/random/boot_id",boot,sizeof(boot))<=0||str_eq(oldboot,boot))continue;
  mod_path(dst,id,"");q=stpcpy_(bak,"/data/adb/modules/.cairn-old-");stpcpy_(q,id);
  if(mod_exists(bak)){loga_str("[module] backup exists, commit deferred:",id);continue;}
  mod_path(flag,id,"/disable");if(mod_exists(flag)){q=stpcpy_(flag,src);stpcpy_(q,"/disable");long f=sc4(SYS_openat,AT_FDCWD,(i64)flag,O_WRONLY|O_CREAT,0600);if(f<0)continue;sc1(SYS_close,f);}
  int had=mod_exists(dst);
  if(had&&sc4(SYS_renameat,AT_FDCWD,(i64)dst,AT_FDCWD,(i64)bak)<0)continue;
  if(sc4(SYS_renameat,AT_FDCWD,(i64)src,AT_FDCWD,(i64)dst)<0){if(had)sc4(SYS_renameat,AT_FDCWD,(i64)bak,AT_FDCWD,(i64)dst);loga_str("[module] commit failed:",id);continue;}
  loga_str("[module] committed:",id);
  if(had && mod_delete(bak,0)<0)loga_str("[module] rollback cleanup pending:",id);
 }
 sc1(SYS_close,fd);
}
