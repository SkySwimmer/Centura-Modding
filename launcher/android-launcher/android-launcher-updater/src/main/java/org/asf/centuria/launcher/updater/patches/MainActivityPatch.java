package org.asf.centuria.launcher.updater.patches;

import org.asf.centuria.launcher.updater.LauncherUpdaterMain;
import org.asf.cyan.fluid.api.FluidTransformer;
import org.asf.cyan.fluid.api.transforming.InjectAt;
import org.asf.cyan.fluid.api.transforming.TargetClass;
import org.asf.cyan.fluid.api.transforming.enums.InjectLocation;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;

@FluidTransformer
@TargetClass(target = "com.unity3d.player.UnityPlayerActivity")
public class MainActivityPatch extends Activity {

	private boolean cancelledByFTL = false;

	@InjectAt(location = InjectLocation.HEAD)
	public void onCreate(Bundle bundle) {
		// Okay fluid injects the bytecode directly into the target, meaning that this
		// is pasted at the start of the method if its HEAD, and at the end if its TAIL
		//
		// We inject into onCreate to hook into unity initialization and jump into the
		// launcher before unity gets a chance to initialize
		//
		// If the launcher signals startup, the jump to the launcher will be skipped
		// and the game will launch normally

		// Call updater main init method and jump away from Unity's code
		if (LauncherUpdaterMain.mainInit(this)) {
			// Cancel
			cancelledByFTL = true;
			super.onCreate(bundle);
			return;
		}

		// Run normal code after this
		return;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public boolean dispatchKeyEvent(KeyEvent arg0) {
		// Check
		if (cancelledByFTL)
			return super.dispatchKeyEvent(arg0);

		// Return
		return false;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public void onConfigurationChanged(Configuration arg0) {
		// Check
		if (cancelledByFTL) {
			super.onConfigurationChanged(arg0);
			return;
		}

		// Return
		return;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public void onDestroy() {
		// Check
		if (cancelledByFTL) {
			super.onDestroy();
			return;
		}

		// Return
		return;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public boolean onKeyDown(int arg0, KeyEvent arg1) {
		// Check
		if (cancelledByFTL)
			return super.onKeyDown(arg0, arg1);

		// Return
		return false;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public boolean onKeyUp(int arg0, KeyEvent arg1) {
		// Check
		if (cancelledByFTL) {
			return super.onKeyUp(arg0, arg1);
		}

		// Return
		return false;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public void onLowMemory() {
		// Check
		if (cancelledByFTL) {
			super.onLowMemory();
			return;
		}

		// Return
		return;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public void onNewIntent(Intent arg0) {
		// Check
		if (cancelledByFTL) {
			super.onNewIntent(arg0);
			return;
		}

		// Return
		return;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public void onPause() {
		// Check
		if (cancelledByFTL) {
			super.onPause();
			return;
		}

		// Return
		return;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public void onResume() {
		// Check
		if (cancelledByFTL) {
			super.onResume();
			return;
		}

		// Return
		return;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public boolean onTouchEvent(MotionEvent arg0) {
		// Check
		if (cancelledByFTL) {
			return super.onTouchEvent(arg0);
		}

		// Return
		return false;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public void onTrimMemory(int arg0) {
		// Check
		if (cancelledByFTL) {
			super.onTrimMemory(arg0);
			return;
		}

		// Return
		return;
	}

	@InjectAt(location = InjectLocation.HEAD)
	public void onWindowFocusChanged(boolean arg0) {
		// Check
		if (cancelledByFTL) {
			super.onWindowFocusChanged(arg0);
			return;
		}

		// Return
		return;
	}

}
