package dev.aura.player;

import static org.junit.Assert.*;import org.junit.Test;import org.junit.runner.RunWith;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;import android.view.accessibility.*;

/** Clear only the platform System UI cold-boot ANR; an AURA crash is never dismissed. */
@RunWith(AndroidJUnit4.class)
public class EmulatorEnvironmentTest {
    @Test public void clearColdBootSystemUiDialog()throws Exception{var automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();var info=automation.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(info);Thread.sleep(300);boolean found=false;for(var window:automation.getWindows()){AccessibilityNodeInfo root=window.getRoot();if(root==null||root.findAccessibilityNodeInfosByText("System UI").isEmpty())continue;found=true;boolean clicked=false;for(var close:root.findAccessibilityNodeInfosByText("Close app")){for(AccessibilityNodeInfo node=close;node!=null;node=node.getParent())if(node.isClickable()){clicked=node.performAction(AccessibilityNodeInfo.ACTION_CLICK);break;}}assertTrue("Platform ANR close action failed",clicked);}if(found)Thread.sleep(1000);for(var window:automation.getWindows()){AccessibilityNodeInfo root=window.getRoot();if(root!=null)assertTrue("System UI ANR still obscures tests",root.findAccessibilityNodeInfosByText("System UI").isEmpty());}}
}
