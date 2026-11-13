from pathlib import Path
import subprocess,zipfile,shutil,xml.etree.ElementTree as ET
W=Path(__file__).resolve().parent;B=W/'build';A=W/'app';R=B/'resources';R.mkdir(exist_ok=True)
def cmd(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True)
 if r.returncode:print(r.stdout,r.stderr);raise RuntimeError(str(args[0]))
 return r.stdout
archives=[];packages=[]
seen=set()
# Prefer named AARs; omit stale aliases and unrelated material3 runtime.
for p in sorted(Path('/opt/kotlin/maven').rglob('*.aar'))+list((W/'deps').glob('*.aar')):
 if not zipfile.is_zipfile(p):continue
 if p.name in seen or p.name in ('ak.aar','ss.aar','vmss.aar','vmss_android.aar','material3-android-1.4.0.aar','core-1.15.0.aar','savedstate-android-1.3.1.aar'):continue
 seen.add(p.name)
 with zipfile.ZipFile(p) as z:
  if 'AndroidManifest.xml' not in z.namelist():continue
  try:pkg=ET.fromstring(z.read('AndroidManifest.xml')).get('package')
  except ET.ParseError:continue
  if not pkg:continue
  packages.append(pkg)
  dest=R/p.stem;dest.mkdir(exist_ok=True)
  for n in z.namelist():
   if n.startswith('res/') and not n.endswith('/'):
    q=dest/n;q.parent.mkdir(parents=True,exist_ok=True);q.write_bytes(z.read(n))
  if (dest/'res').exists():
   out=dest/'compiled.zip';cmd(['aapt2','compile','--dir',dest/'res','-o',out]);archives.append(out)
appres=R/'app.zip';cmd(['aapt2','compile','--dir',A/'res','-o',appres]);archives.append(appres)
m=(A/'AndroidManifest.xml').read_text().replace('android:minSdkVersion="23"','android:minSdkVersion="26"');manifest=B/'AndroidManifest.xml';manifest.write_text(m)
gen=B/'gen';gen.mkdir(exist_ok=True)
args=['aapt2','link','-I','/usr/lib/android-sdk/platforms/android-33/android.jar','--manifest',manifest,'--java',gen,'--extra-packages',':'.join(sorted(set(packages))),'--auto-add-overlay','-o',B/'resources.apk']
for p in archives:args+=['-R',p]
cmd(args)
print('Resources linked',len(archives),'packages',len(packages))
# Generate actual R classes for library bytecode and recompile manager against linked app R.
cp=':'.join(str(p) for p in Path('/opt/kotlin/maven/cls').glob('*.jar'))+':/usr/lib/android-sdk/platforms/android-33/android.jar:'+str(B/'cairn-compose-ui.jar')
classes=B/'manager-classes';shutil.rmtree(classes);classes.mkdir()
cmd(['javac','--release','17','-classpath',cp,'-d',classes]+list((A/'src').rglob('*.java'))+list(gen.rglob('*.java')))
cmd(['python3',W/'compile-dex.py'])
unsigned=B/'candidate-unsigned.apk';shutil.copyfile(B/'resources.apk',unsigned)
with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
 for p in (B/'dex').glob('*.dex'):z.write(p,p.name)
 for p in (W.parent/'source/app/build/lib/arm64-v8a').glob('*.so'):z.write(p,'lib/arm64-v8a/'+p.name)
 z.write(A/'assets/module-install.sh','assets/module-install.sh')
 # Do not distribute the old unknown-origin dex or jar.
cmd(['zipalign','-f','4',unsigned,B/'candidate-aligned.apk'])
print('Unsigned migration candidate:',B/'candidate-aligned.apk')
