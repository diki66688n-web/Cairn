#!/system/bin/sh
# Cairn installer environment. No policy or overlay helpers.
MODPATH=$1
ZIPFILE=$2
export MODPATH ZIPFILE
export BOOTMODE=true CAIRN=true CAIRN_API=1 KSU=true KSU_VER=Cairn KSU_VER_CODE=0
export ARCH=arm64 IS64BIT=true API=$(/system/bin/getprop ro.build.version.sdk)
export PATH=/data/adb/bin:/system/bin
ui_print() { printf '%s\n' "$*"; }
abort() { ui_print "! $*"; exit 1; }
# Never silently fake permission success or accept a SELinux context argument.
set_perm() {
 [ "$#" -eq 4 ] || abort 'SELinux context argument is unsupported'
 chown "$1:$2" "$4" && chmod "$3" "$4" || abort 'set_perm failed'
}
set_perm_recursive() {
 [ "$#" -eq 5 ] || abort 'SELinux context argument is unsupported'
 chown -R "$1:$2" "$5" || abort 'chown failed'
 find "$5" -type d -exec chmod "$3" '{}' ';' || abort 'directory chmod failed'
 find "$5" -type f -exec chmod "$4" '{}' ';' || abort 'file chmod failed'
}
ui_print '- Cairn: no mounting, no SELinux policy changes'
[ -d "$MODPATH" ] || abort 'missing staging directory'
cd "$MODPATH" || abort 'cannot enter staging'
if [ -f customize.sh ]; then
 ui_print '- Running customize.sh'
 . ./customize.sh || abort 'customize.sh failed'
else
 ui_print '- No customize.sh, default extraction only'
fi
# Check again because customization can create new module payload.
for f in sepolicy.rule system vendor product system_ext odm; do
 [ ! -e "$MODPATH/$f" ] || abort "Unsupported payload: $f"
done
ui_print '- Customization completed'
