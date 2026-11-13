/* Central persistent UID list. All callers serialize through allowlist.lock.
 * Canonical format: one decimal UID per line. No per-application files.
 */
#ifndef CAIRN_ALLOWLIST
#define CAIRN_ALLOWLIST CAIRN_BASE "/allowlist"
#endif
#define AL_MAX 128
static u32 al_uid[AL_MAX];
static u32 al_count;
static char al_text[AL_MAX*12+1];
static i64 al_lock_fd = -1;
static int al_lock(void) {
    al_lock_fd=sc4(SYS_openat,AT_FDCWD,(i64)(CAIRN_ALLOWLIST ".lock"),O_CREAT|O_WRONLY,0600);
    if(al_lock_fd<0) return -1;
    if(sc3(32 /* flock */,al_lock_fd,2 /* LOCK_EX */,0)<0){sc1(SYS_close,al_lock_fd);al_lock_fd=-1;return -1;}
    return 0;
}
static void al_unlock(void) {
    if(al_lock_fd>=0){sc1(SYS_close,al_lock_fd);al_lock_fd=-1;}
}
static int al_load(void) {
    al_count=0;
    i64 fd=sc4(SYS_openat,AT_FDCWD,(i64)CAIRN_ALLOWLIST,O_RDONLY,0);
    if(fd==-2) return 0;
    if(fd<0) return -1;
    i64 n=0;
    for(;;){i64 r=sc3(SYS_read,fd,(i64)(al_text+n),sizeof(al_text)-n);
        if(r<0){sc1(SYS_close,fd);return -1;} if(!r)break;n+=r;
        if(n==(i64)sizeof(al_text)){sc1(SYS_close,fd);return -1;}}
    sc1(SYS_close,fd);
    u64 v=0; int digits=0;
    for(i64 i=0;i<=n;i++) {
        char c=i<n?al_text[i]:'\n';
        if(c>='0'&&c<='9'){v=v*10+(c-'0');if(v>4294967295UL)return -1;digits=1;continue;}
        if(c!='\n'&&c!='\r')return -1;
        if(!digits)continue;
        if(v<10000)return -1;
        for(u32 j=0;j<al_count;j++)if(al_uid[j]==v)return -1;
        if(al_count==AL_MAX)return -1;
        al_uid[al_count++]=(u32)v;v=0;digits=0;
    }
    return 0;
}
static int al_save(void) {
    u32 n=0;
    for(u32 i=0;i<al_count;i++){n+=utoa(al_uid[i],al_text+n);al_text[n++]='\n';}
    sc3(SYS_unlinkat,AT_FDCWD,(i64)(CAIRN_ALLOWLIST ".tmp"),0);
    i64 fd=sc4(SYS_openat,AT_FDCWD,(i64)(CAIRN_ALLOWLIST ".tmp"),O_CREAT|O_WRONLY|O_TRUNC,0600);
    if(fd<0)return -1;
    u32 off=0;int ok=1;
    while(off<n){i64 r=sc3(SYS_write,fd,(i64)(al_text+off),n-off);if(r<=0){ok=0;break;}off+=(u32)r;}
    if(sc1(82 /* fsync */,fd)<0)ok=0;
    sc1(SYS_close,fd);
    if(!ok){sc3(SYS_unlinkat,AT_FDCWD,(i64)(CAIRN_ALLOWLIST ".tmp"),0);return -1;}
    if(sc4(38 /* renameat */,AT_FDCWD,(i64)(CAIRN_ALLOWLIST ".tmp"),AT_FDCWD,(i64)CAIRN_ALLOWLIST)<0)return -1;
    return 0;
}
static int al_update(u32 uid,int grant) {
    if(al_lock()<0)return -1;
    if(al_load()<0){al_unlock();return -1;}
    u32 i;for(i=0;i<al_count;i++)if(al_uid[i]==uid)break;
    if(grant&&i==al_count){if(al_count==AL_MAX){al_unlock();return -1;}al_uid[al_count++]=uid;}
    if(!grant&&i<al_count){for(u32 j=i+1;j<al_count;j++)al_uid[j-1]=al_uid[j];al_count--;}
    int r=al_save();al_unlock();return r;
}
#define CAIRN_POL_NONE 0
#define CAIRN_POL_CONFIRM 1
#define CAIRN_POL_AUTO 2
static int auth_policy(u32 uid) {
    if(al_lock()<0)return 0;
    int policy=0;
    if(al_load()==0)for(u32 i=0;i<al_count;i++)if(al_uid[i]==uid)policy=CAIRN_POL_AUTO;
    al_unlock();return policy;
}
static int is_authorized(u32 uid){return auth_policy(uid)!=0;}
static int do_grant_policy(u32 uid,int mode){(void)mode;return al_update(uid,1);}
static int do_grant(u32 uid){return al_update(uid,1);}
static int do_revoke(u32 uid){return al_update(uid,0);}
