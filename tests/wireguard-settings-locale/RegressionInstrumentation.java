// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.
package app.morphe.wireguard.locale;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toolbar;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.AppLanguage;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.wireguard.WireGuardConfigStore;
import app.morphe.extension.shared.wireguard.WireGuardProxy;
import app.morphe.extension.shared.wireguard.WireGuardSettingsActivity;

/** Actual Android Activity and widget regression, with inert transport/settings collaborators. */
public final class RegressionInstrumentation extends Instrumentation {
    private static final long DIALOG_TIMEOUT_MS=10_000;
    private static final long DIALOG_POLL_MS=50;
    private static final int DIALOG_STABLE_POLLS=3;
    private Bundle args;
    private final List<String> failures=new ArrayList<>();
    private int checks;
    private String scenario;
    private String localLanguage;
    private Activity settings;
    private UiAutomation automation;

    @Override public void onCreate(Bundle arguments) { args=arguments;start(); }

    private Context locale(Context context,String language) {
        Configuration c=new Configuration(context.getResources().getConfiguration());
        c.setLocale(Locale.forLanguageTag(language));return context.createConfigurationContext(c);
    }

    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)
            throws InstantiationException,IllegalAccessException,ClassNotFoundException {
        Activity activity=super.newActivity(loader,name,intent);
        Configuration c=new Configuration();
        c.setLocale(Locale.forLanguageTag(name.endsWith("HostActivity") ? "en" : localLanguage));
        activity.applyOverrideConfiguration(c);
        return activity;
    }

    @Override public void callActivityOnCreate(Activity activity,Bundle state) {
        // Headless tests must not depend on a fixture's keyguard or display sleep state.
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        super.callActivityOnCreate(activity,state);
    }

    private void equal(String label,CharSequence actual,String expected) {
        checks++; if(!expected.contentEquals(actual==null ? "<null>" : actual)) {
            failures.add(label+": expected="+expected+"; actual="+actual);
        }
    }

    private String text(String key) {
        return settings.getString(settings.getResources().getIdentifier(key,"string",settings.getPackageName()));
    }

    private void invoke(String name,Class<?>[] types,Object... values) {
        try { Method m=WireGuardSettingsActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(settings,values); }
        catch(Exception e){throw new RuntimeException(e);}
    }

    private void refresh() { invoke("refreshState",new Class<?>[0]); }

    private Toolbar toolbar(View view) {
        if(view instanceof Toolbar t)return t;
        if(view instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){Toolbar t=toolbar(g.getChildAt(i));if(t!=null)return t;}
        return null;
    }

    private void initializeAccessibility() {
        automation=getUiAutomation();
        AccessibilityServiceInfo info=automation.getServiceInfo();
        if(info==null)throw new IllegalStateException("Accessibility service is unavailable");
        info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
    }

    private boolean hasDialogButton(AccessibilityNodeInfo root) {
        boolean visible=false;
        for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByViewId("android:id/button1")) {
            visible|=node.isVisibleToUser();
            node.recycle();
        }
        return visible;
    }

    private AccessibilityNodeInfo awaitDialogState(String name,boolean shown) throws Exception {
        final long deadline=SystemClock.uptimeMillis()+DIALOG_TIMEOUT_MS;
        int stable=0;
        int previousWindow=-1;
        boolean rootPresent=false;
        boolean buttonVisible=false;
        while(SystemClock.uptimeMillis()<deadline) {
            AccessibilityNodeInfo root=automation.getRootInActiveWindow();
            rootPresent=root!=null;
            if(rootPresent) {
                buttonVisible=hasDialogButton(root);
                final int window=root.getWindowId();
                if(buttonVisible==shown) {
                    stable=window==previousWindow ? stable+1 : 1;
                    previousWindow=window;
                    if(stable>=DIALOG_STABLE_POLLS)return root;
                } else {
                    stable=0;
                    previousWindow=-1;
                }
                root.recycle();
            } else {
                stable=0;
                previousWindow=-1;
            }
            Thread.sleep(DIALOG_POLL_MS);
        }
        // Synchronization depends only on structure, never the expected translation.
        throw new IllegalStateException("Dialog fixture timeout: "+name+", shown="+shown
                +", rootPresent="+rootPresent+", positiveButtonVisible="+buttonVisible);
    }

    private List<String> openDialog(String name,Runnable open) throws Exception {
        awaitDialogState(name+" before opening",false).recycle();
        runOnMainSync(open);
        waitForIdleSync();
        List<String> result=new ArrayList<>();
        collect(awaitDialogState(name+" opening",true),result);
        return result;
    }

    private void dismissDialog(String name) throws Exception {
        if(!automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) {
            throw new IllegalStateException("Could not dismiss dialog fixture: "+name);
        }
        waitForIdleSync();
        awaitDialogState(name+" dismissal",false).recycle();
    }

    private void collect(AccessibilityNodeInfo node,List<String> out) {
        if(node==null)return;
        if(node.getText()!=null)out.add(node.getText().toString());
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);collect(child,out);}
        node.recycle();
    }

    private void dialog(String name,Runnable open,String... expectedKeys) throws Exception {
        List<String> shown=openDialog(name,open);
        for(String key:expectedKeys){checks++;String expected=text(key);if(!shown.contains(expected))failures.add(name+": missing "+expected+"; visible="+shown);}
        dismissDialog(name);
    }

    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            initializeAccessibility();
            scenario=args.getString("scenario","activity-ko-cached-en");
            localLanguage=scenario.equals("english-fallback") ? "zz" : "ko";
            Context base=getTargetContext();
            Utils.context=locale(base,scenario.equals("english-fallback") ? "ko" : "en");
            HostActivity host=(HostActivity)startActivitySync(new Intent().setClassName(base.getPackageName(),HostActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Utils.activity=host;
            if(scenario.equals("english-fallback"))ResourceUtils.useActivityContextIfAvailable=false;
            List<String> keys=new ArrayList<>();
            for(String key:args.getString("keys").split(","))keys.add(key);
            if(!scenario.equals("activity-ko-unprimed"))for(String key:keys)StringRef.str(key);
            if(scenario.equals("morphe-ko-cached-en")) {
                Utils.context=locale(base,"ko");BaseSettings.MORPHE_LANGUAGE.value=AppLanguage.KO;
                ResourceUtils.useActivityContextIfAvailable=false;
            }
            EntryActivity entry=(EntryActivity)startActivitySync(new Intent().setClassName(base.getPackageName(),EntryActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(()->{
                int title=entry.getResources().getIdentifier("morphe_wireguard_settings_title","string",entry.getPackageName());
                equal("entry XML title",entry.getPreferenceScreen().getPreference(0).getTitle(),entry.getString(title));
                entry.finish();
            });
            settings=startActivitySync(new Intent().setClassName(base.getPackageName(),WireGuardSettingsActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(()->{
                equal("activity title",settings.getTitle(),text("morphe_wireguard_settings_title"));
                Toolbar bar=toolbar(settings.getWindow().getDecorView());
                equal("toolbar title",bar.getTitle(),text("morphe_wireguard_settings_title"));
                equal("navigation description",bar.getNavigationContentDescription(),text("morphe_settings_navigate_up"));
                WireGuardSettingsActivity.SettingsFragment fragment=(WireGuardSettingsActivity.SettingsFragment)settings.getFragmentManager().findFragmentById(settings.getResources().getIdentifier("morphe_settings_fragments","id",settings.getPackageName()));
                PreferenceScreen screen=fragment.getPreferenceScreen();
                String[] titles={"enabled_title","status_title","import","remove","restart","licenses"};
                for(int i=0;i<titles.length;i++)equal("row "+i,screen.getPreference(i).getTitle(),text("morphe_wireguard_"+titles[i]));
                equal("disabled state",screen.getPreference(1).getSummary(),text("morphe_wireguard_state_disabled"));
                equal("disabled summary",screen.getPreference(0).getSummary(),text("morphe_wireguard_saved_disabled"));
                equal("missing profile",screen.getPreference(2).getSummary(),text("morphe_wireguard_profile_missing"));
                WireGuardConfigStore.present=true;SharedYouTubeSettings.WIREGUARD_ENABLED.value=true;
                WireGuardProxy.restart=true;WireGuardProxy.current=WireGuardProxy.State.READY;refresh();
                equal("ready state",screen.getPreference(1).getSummary(),text("morphe_wireguard_state_ready"));
                equal("enabled summary",screen.getPreference(0).getSummary(),text("morphe_wireguard_saved_enabled"));
                equal("present profile",screen.getPreference(2).getSummary(),text("morphe_wireguard_profile_present"));
                equal("restart needed",screen.getPreference(4).getSummary(),text("morphe_wireguard_restart_needed"));
                WireGuardProxy.current=WireGuardProxy.State.BLOCKED;refresh();
                equal("blocked state",screen.getPreference(1).getSummary(),text("morphe_wireguard_state_blocked"));
                WireGuardProxy.current=WireGuardProxy.State.NOT_STARTED;refresh();
                equal("pending state",screen.getPreference(1).getSummary(),text("morphe_wireguard_state_pending"));
                try {Field busy=WireGuardSettingsActivity.class.getDeclaredField("busy");busy.setAccessible(true);busy.setBoolean(settings,true);refresh();
                    equal("busy state",screen.getPreference(1).getSummary(),text("morphe_wireguard_state_pending")+"\n\n"+text("morphe_wireguard_busy"));busy.setBoolean(settings,false);refresh();}
                catch(Exception e){throw new RuntimeException(e);}
            });
            dialog("error",()->invoke("showProfileFailure",new Class<?>[]{String.class,String.class},"morphe_wireguard_validation_failed","morphe_wireguard_error_invalid_endpoint"),"morphe_wireguard_profile_error_title");
            // Combined message is one real TextView, so verify both detail components together.
            List<String> shown=openDialog("error details",()->invoke("showProfileFailure",new Class<?>[]{String.class,String.class},"morphe_wireguard_validation_failed","morphe_wireguard_error_invalid_endpoint"));
            String combined=text("morphe_wireguard_validation_failed")+"\n\n"+text("morphe_wireguard_error_invalid_endpoint");checks++;if(!shown.contains(combined))failures.add("error details: expected="+combined+"; visible="+shown);
            dismissDialog("error details");
            dialog("disable confirmation",()->invoke("confirmToggle",new Class<?>[0]),"morphe_wireguard_settings_title","morphe_wireguard_disable_confirm","morphe_wireguard_apply_restart");
            SharedYouTubeSettings.WIREGUARD_ENABLED.value=false;
            dialog("enable confirmation",()->invoke("confirmToggle",new Class<?>[0]),"morphe_wireguard_settings_title","morphe_wireguard_enable_confirm","morphe_wireguard_apply_restart");
            dialog("remove confirmation",()->invoke("confirmRemove",new Class<?>[0]),"morphe_wireguard_remove","morphe_wireguard_remove_confirm","morphe_wireguard_apply_restart");
            runOnMainSync(()->settings.finish());
            result.putString("status",failures.isEmpty()?"PASS":"FAIL");result.putString("scenario",scenario);result.putInt("checks",checks);result.putString("failures",failures.toString());
            finish(failures.isEmpty()?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        } catch(Throwable t) {result.putString("status","ERROR");result.putString("error",android.util.Log.getStackTraceString(t));finish(Activity.RESULT_CANCELED,result);}
    }
}
