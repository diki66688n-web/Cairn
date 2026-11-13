from pathlib import Path
import zipfile,subprocess
W=Path(__file__).resolve().parent;B=W/'build';B.mkdir(exist_ok=True)
# Explicitly exclude stale aliases; never bundle unresolved LiquidGlass binary.
skip={'ss.jar','vmss.jar','vmss_android.jar','core-runtime.jar','core-1.15.0.jar','savedstate-android-1.3.1.jar','collection-jvm-1.4.2.jar'}
seen=set();jar=B/'compose-runtime-merged.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as out:
 for p in sorted(Path('/opt/kotlin/maven/cls').glob('*.jar'))+list((W/'deps').glob('*.jar')):
  if p.name in skip:continue
  with zipfile.ZipFile(p) as z:
   for n in z.namelist():
    if not n.endswith('.class') or n.endswith('module-info.class') or n in seen:continue
    seen.add(n);out.writestr(n,z.read(n))
 for p in [B/'cairn-compose-ui.jar']:
  with zipfile.ZipFile(p) as z:
   for n in z.namelist():
    if n.endswith('.class'):assert n not in seen;seen.add(n);out.writestr(n,z.read(n))
 for p in (B/'manager-classes').rglob('*.class'):
  n=p.relative_to(B/'manager-classes').as_posix();assert n not in seen;seen.add(n);out.writestr(n,p.read_bytes())
out=B/'dex';out.mkdir(exist_ok=True)
with (B/'d8.log').open('w') as f:
 r=subprocess.run(['java','-Xmx2g','-cp','/opt/kotlin/tools/r8.jar','com.android.tools.r8.D8','--min-api','26','--lib','/usr/lib/android-sdk/platforms/android-33/android.jar','--output',str(out),str(jar)],stdout=f,stderr=subprocess.STDOUT)
print('D8 RC',r.returncode,'classes',len(seen));print((B/'d8.log').read_text()[-3500:]);assert r.returncode==0
print([p.name for p in out.glob('*.dex')])
