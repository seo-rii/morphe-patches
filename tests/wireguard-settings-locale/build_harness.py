#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# See the included NOTICE file for additional GPLv3 Section 7 requirements.
"""Build a standalone Android WireGuard settings locale regression APK.

Real sources: Activity, BaseActivityHook, StringRef, ResourceUtils, ResourceType.
Real Android runtime resources/widgets/dialogs; collaborators are explicit stubs.
This is not a YouTube/Music integration test. No native transport is exercised.
"""
import argparse
import hashlib
import json
import subprocess
import textwrap
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile


STUBS = {
    'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
    'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
    'androidx/annotation/ColorInt.java': 'package androidx.annotation; public @interface ColorInt {}',
    'app/morphe/extension/shared/Logger.java': '''
        package app.morphe.extension.shared;
        import java.util.function.Supplier;
        public final class Logger {
          public static void printException(Supplier<String> s) { android.util.Log.e("WG-Locale",s.get()); }
          public static void printException(Supplier<String> s, Throwable t) { android.util.Log.e("WG-Locale",s.get(),t); }
        }''',
    'app/morphe/extension/shared/Utils.java': '''
        package app.morphe.extension.shared;
        import android.app.Activity;
        import android.content.Context;
        import android.content.res.Resources;
        import android.view.View;
        import android.view.ViewGroup;
        import java.util.function.Predicate;
        public final class Utils {
          public static Context context; public static Activity activity;
          public static Context getContext() { return context; }
          public static Activity getActivity() { return activity; }
          public static Resources getResources() { return context.getResources(); }
          public static void setIsDarkModeEnabled(boolean v) {}
          public static boolean isDarkModeEnabled() { return false; }
          public static void restartApp(Activity a) { throw new AssertionError("Restart is outside this test"); }
          @SuppressWarnings("unchecked") public static <T extends View> T getChildView(ViewGroup p, boolean nested, Predicate<View> predicate) {
            for (int i=0;i<p.getChildCount();i++) { View v=p.getChildAt(i); if(predicate.test(v)) return (T)v;
              if(nested && v instanceof ViewGroup g) { T result=getChildView(g,true,predicate); if(result!=null)return result; }
            } return null;
          }
          @SuppressWarnings("unchecked") public static <T extends View> T getChildViewByResourceName(ViewGroup p, String name) {
            return (T)p.findViewById(p.getResources().getIdentifier(name,"id",p.getContext().getPackageName()));
          }
        }''',
    'app/morphe/extension/shared/settings/Setting.java': 'package app.morphe.extension.shared.settings; public class Setting<T> { public String key; }',
    'app/morphe/extension/shared/settings/AppLanguage.java': 'package app.morphe.extension.shared.settings; public enum AppLanguage { DEFAULT,KO,EN }',
    'app/morphe/extension/shared/settings/BaseSettings.java': '''
        package app.morphe.extension.shared.settings;
        public final class BaseSettings { public static final Language MORPHE_LANGUAGE=new Language();
          public static final class Language { public AppLanguage value=AppLanguage.DEFAULT; public AppLanguage get(){return value;} }
        }''',
    'app/morphe/extension/shared/settings/SharedYouTubeSettings.java': '''
        package app.morphe.extension.shared.settings;
        public final class SharedYouTubeSettings { public static final Flag WIREGUARD_ENABLED=new Flag();
          public static final class Flag { public boolean value; public boolean get(){return value;} public void save(boolean v){value=v;} }
        }''',
    'app/morphe/extension/shared/settings/preference/AbstractPreferenceFragment.java': '''
        package app.morphe.extension.shared.settings.preference;
        public final class AbstractPreferenceFragment { public static boolean showRestartDialogIfNeeded(android.app.Activity a){return false;} }
        ''',
    'app/morphe/extension/shared/settings/preference/ToolbarPreferenceFragment.java': '''
        package app.morphe.extension.shared.settings.preference;
        import android.app.Activity; import android.preference.PreferenceFragment; import android.graphics.drawable.Drawable;
        import android.widget.Toolbar; import android.widget.ImageButton; import android.view.Window;
        public class ToolbarPreferenceFragment extends PreferenceFragment {
          public static Drawable getBackButtonDrawable(){return null;}
          public static void setNavigationBarColor(Window w){}
          public static void addToolbarButton(Toolbar toolbar,String icon,String description,Runnable click){
            ImageButton button=new ImageButton(toolbar.getContext());button.setContentDescription(description);
            button.setOnClickListener(v->click.run());toolbar.addView(button);
          }
        }''',
    'app/morphe/extension/shared/theme/ThemeUtils.java': '''
        package app.morphe.extension.shared.theme;
        public final class ThemeUtils { public static int getAppBackgroundColor(){return 0xffffffff;} public static int getAppForegroundColor(){return 0xff000000;} }
        ''',
    'app/morphe/extension/shared/ui/Dim.java': 'package app.morphe.extension.shared.ui; public final class Dim { public static int dp16=16; }',
    'app/morphe/extension/shared/patches/SettingsNamePatch.java': 'package app.morphe.extension.shared.patches; public final class SettingsNamePatch { public static String getSettingsName(){return "Morphe";} }',
    'app/morphe/extension/shared/wireguard/WireGuardConfigStore.java': '''
        package app.morphe.extension.shared.wireguard;
        public final class WireGuardConfigStore { public static boolean present; public static boolean hasProfile(android.content.Context c){return present;} public static void delete(android.content.Context c){present=false;} }
        ''',
    'app/morphe/extension/shared/wireguard/WireGuardProxy.java': '''
        package app.morphe.extension.shared.wireguard;
        public final class WireGuardProxy {
          public enum State {DISABLED,NOT_STARTED,READY,BLOCKED}
          public static State current=State.DISABLED; public static boolean restart;
          public static State state(){return current;} public static boolean isPatchIncluded(){return true;}
          public static boolean isRestartRequired(){return restart;} public static void markRestartRequired(){restart=true;}
        }''',
    'app/morphe/extension/shared/wireguard/ProfileOperations.java': '''
        package app.morphe.extension.shared.wireguard;
        public final class ProfileOperations {
          public interface Source { java.io.InputStream open() throws java.io.IOException; }
          public static final class Failure extends Exception { public String resourceKey,validationReasonKey; }
          public static void importProfile(android.content.Context c,Source source) throws Failure {throw new AssertionError("Import outside test");}
          public static void validateStoredProfile(android.content.Context c) throws Failure {throw new AssertionError("Validation outside test");}
        }''',
    'app/morphe/wireguard/locale/HostActivity.java': 'package app.morphe.wireguard.locale; public final class HostActivity extends android.app.Activity {}',
    'app/morphe/wireguard/locale/EntryActivity.java': '''
        package app.morphe.wireguard.locale;
        public final class EntryActivity extends android.preference.PreferenceActivity {
          @Override public void onCreate(android.os.Bundle state) { super.onCreate(state);addPreferencesFromResource(getResources().getIdentifier("entry","xml",getPackageName())); }
        }''',
}


def run(command, **kwargs):
    subprocess.run(list(map(str, command)), check=True, **kwargs)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', required=True, type=Path)
    p.add_argument('--library', required=True, type=Path, help='morphe-patches-library checkout')
    p.add_argument('--korean-resources', required=True, type=Path, help='Real custom XML or clearly marked test-only fixture')
    p.add_argument('--sdk', required=True, type=Path)
    p.add_argument('--jdk', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--variant', choices=('baseline', 'fixed'), required=True)
    p.add_argument('--baseline-commit', help='Read Activity/BaseActivityHook from this Git commit')
    a = p.parse_args()
    assert not a.output.exists(), 'Choose a fresh output directory'
    a.output.mkdir(parents=True, mode=0o700)
    src = a.output / 'src'; src.mkdir()
    copied = []
    def write(name, data, origin):
        data = data.encode() if isinstance(data, str) else data
        dest = src / name; dest.parent.mkdir(parents=True, exist_ok=True); dest.write_bytes(data)
        copied.append({'path': name, 'sha256': hashlib.sha256(data).hexdigest(), 'origin': origin})
    base = 'extensions/shared-youtube/library/src/main/java/app/morphe/extension/shared/'
    for name in ('wireguard/WireGuardSettingsActivity.java', 'settings/BaseActivityHook.java'):
        data = (subprocess.check_output(['git', 'show', a.baseline_commit + ':' + base + name], cwd=a.source)
                if a.baseline_commit else (a.source / base / name).read_bytes())
        write('app/morphe/extension/shared/' + name, data, ('git:' + a.baseline_commit) if a.baseline_commit else 'current production source')
    for name in ('StringRef.java', 'ResourceUtils.java', 'ResourceType.java'):
        data = (a.library / 'extension-library/src/main/java/app/morphe/extension/shared' / name).read_bytes()
        write('app/morphe/extension/shared/' + name, data, 'exact public extension dependency')
    for name, text in STUBS.items(): write(name, textwrap.dedent(text).strip() + '\n', 'explicit collaborator stub; not production')
    write('app/morphe/wireguard/locale/RegressionInstrumentation.java', Path(__file__).with_name('RegressionInstrumentation.java').read_bytes(), 'Android runtime test runner')
    res = a.output / 'res'; (res / 'values').mkdir(parents=True); (res / 'values-ko').mkdir(); (res / 'layout').mkdir(); (res / 'xml').mkdir()
    extra = {'morphe_settings_navigate_up', 'morphe_settings_screen_info_title'}
    locale_tables = {}
    for origin, folder in ((a.source / 'patches/src/main/resources/addresources/values/shared-youtube/strings.xml', 'values'), (a.korean_resources, 'values-ko')):
        root = ET.Element('resources')
        for element in ET.parse(origin).getroot():
            key = element.get('name', '')
            if key.startswith('morphe_wireguard_') or key in extra: root.append(element)
        common = (origin.parent.parent / 'shared/strings.xml' if folder == 'values'
                  else a.source / 'patches/src/main/resources/addresources/values-ko-rKR/shared/strings.xml')
        for element in ET.parse(common).getroot():
            if element.get('name', '') in extra: root.append(element)
        names = [e.get('name') for e in root]
        assert len(names) == len(set(names)), 'Duplicate fixture resource names'
        strings = {e.get('name'): ''.join(e.itertext()) for e in root if e.get('name','').startswith('morphe_wireguard_')}
        assert len(strings) == 60 and all(v.strip() for v in strings.values()), 'Expected 60 nonempty WireGuard resources'
        locale_tables[folder] = strings
        ET.ElementTree(root).write(res / folder / 'strings.xml', encoding='utf-8', xml_declaration=True)
    assert locale_tables['values'].keys() == locale_tables['values-ko'].keys(), 'English/Korean resource key sets differ'
    for key in ('morphe_wireguard_import', 'morphe_wireguard_saved_disabled', 'morphe_wireguard_error_invalid_endpoint'):
        assert locale_tables['values'][key] != locale_tables['values-ko'][key], 'Korean fixture accidentally contains English text: ' + key
    (res / 'values/styles.xml').write_text('<resources><style name="Theme.YouTube.Settings" parent="android:style/Theme.Material.Light.NoActionBar"/><style name="Theme.YouTube.Settings.Dark" parent="android:style/Theme.Material.NoActionBar"/></resources>')
    (res / 'layout/morphe_settings_with_toolbar.xml').write_text('''<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="vertical" android:layout_width="match_parent" android:layout_height="match_parent"><FrameLayout android:id="@+id/morphe_toolbar_parent" android:layout_width="match_parent" android:layout_height="wrap_content"><Toolbar android:id="@+id/morphe_toolbar" android:layout_width="match_parent" android:layout_height="?android:attr/actionBarSize"/></FrameLayout><FrameLayout android:id="@+id/morphe_settings_fragments" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1"/></LinearLayout>''')
    (res / 'xml/entry.xml').write_text('''<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"><Preference android:key="wireguard" android:title="@string/morphe_wireguard_settings_title"/></PreferenceScreen>''')
    package = 'app.morphe.wireguard.locale.' + a.variant
    manifest = a.output / 'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="'''+package+'''" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="28"/><application android:theme="@android:style/Theme.Material.Light.NoActionBar" android:debuggable="true" android:label="WireGuard locale regression"><activity android:name="app.morphe.wireguard.locale.HostActivity" android:exported="false"/><activity android:name="app.morphe.wireguard.locale.EntryActivity" android:exported="false"/><activity android:name="app.morphe.extension.shared.wireguard.WireGuardSettingsActivity" android:exported="false"/></application><instrumentation android:name="app.morphe.wireguard.locale.RegressionInstrumentation" android:targetPackage="'''+package+'''"/></manifest>''')
    tools = a.sdk / 'build-tools/36.0.0'; android = a.sdk / 'platforms/android-36/android.jar'
    classes = a.output / 'classes'; classes.mkdir(); dex = a.output / 'dex'; dex.mkdir()
    run([a.jdk / 'bin/javac', '--release', '17', '-cp', android, '-d', classes, *sorted(src.rglob('*.java'))])
    run([a.jdk / 'bin/jar', 'cf', a.output / 'classes.jar', '-C', classes, '.'])
    run([tools / 'aapt2', 'compile', '--dir', res, '-o', a.output / 'resources.zip'])
    run([tools / 'aapt2', 'link', '-I', android, '--manifest', manifest, '-o', a.output / 'unsigned.apk', a.output / 'resources.zip'])
    run([tools / 'd8', '--min-api', '26', '--lib', android, '--output', dex, a.output / 'classes.jar'])
    with zipfile.ZipFile(a.output / 'unsigned.apk', 'a') as z: z.write(dex / 'classes.dex', 'classes.dex')
    key = a.output / 'ephemeral-test-only.jks'
    run([a.jdk / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android', '-alias', 'test', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-dname', 'CN=Ephemeral Locale Regression Test'])
    run([tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--out', a.output / 'harness.apk', a.output / 'unsigned.apk'])
    resources = {str(f.relative_to(res)): hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(res.rglob('*')) if f.is_file()}
    (a.output / 'build-result.json').write_text(json.dumps({'status':'PASS','variant':a.variant,'package':package,'apk_sha256':hashlib.sha256((a.output/'harness.apk').read_bytes()).hexdigest(),'source_snapshot':copied,'resource_snapshot':resources,'korean_original_sha256':hashlib.sha256(a.korean_resources.read_bytes()).hexdigest(),'scope':'Standalone real Android UI with documented collaborator stubs; not target-app acceptance'},indent=2)+'\n')
    print('PASS built', a.output / 'harness.apk', flush=True)


if __name__ == '__main__': main()
