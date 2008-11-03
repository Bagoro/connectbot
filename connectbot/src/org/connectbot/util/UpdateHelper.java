/*
	ConnectBot: simple, powerful, open-source SSH client for Android
	Copyright (C) 2007-2008 Kenny Root, Jeffrey Sharkey
	
	This program is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	
	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.
	
	You should have received a copy of the GNU General Public License
	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.connectbot.util;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

import org.connectbot.R;
import org.json.JSONObject;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * Helper class that checks for updates to this application. On construction, it
 * spawns a background thread that checks for any app updates. If available,
 * shows a dialog to the user, prompting them to visit Market for the upgrade.
 * 
 * <b>Be sure to change the UPDATE_URL field before using this class.</b> Then
 * place a text file at that URL containing JSON data in the format:
 * 
 * <code>{"versionCode": 110, "features": "Brand new interface with over
 * 9,000 improvements.", "target": "search?q=searchterms"}</code>
 * 
 * Which should contain information about your newest version. The
 * <code>target</code> field is used to build an Intent that launches Market on
 * the device, simply be prefixing it with <code>market://</code>. If you know
 * your exact Market ID, you could use the value
 * <code>details?id=yourexactmarketid</code>
 * 
 * If you're looking for an advanced version-checking system that offers more
 * customization, check out Veecheck: http://www.tomgibara.com/android/veecheck/
 * 
 * @author jsharkey
 */
public class UpdateHelper implements Runnable {
	
	public final static String TAG = UpdateHelper.class.toString();
	public final static String UPDATE_URL = "http://connectbot.org/version";

	protected Context context;
	
	protected String packageName, versionName;
	protected int versionCode;
	
	protected String userAgent;

	/**
	 * Constructor will automatically spawn thread to check for updates.
	 * Recommended usage is <code>new UpdateHelper(this);</code> in the first
	 * onCreate() of your app.
	 */
	public UpdateHelper(Context context) {
		this.context = context;

		try {
			// read current version information about this package
			PackageManager manager = context.getPackageManager();
			PackageInfo info = manager.getPackageInfo(context.getPackageName(), 0);
			this.packageName = info.packageName;
			this.versionCode = info.versionCode;
			this.versionName = info.versionName;

		} catch(Exception e) {
			Log.e(TAG, "Couldn't find package information in PackageManager", e);
			return;
			
		}
		
		// decide if we really need to check for update
		Resources res = context.getResources();
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		
		String frequency = prefs.getString(res.getString(R.string.pref_update), res.getString(R.string.list_update_daily));
		long lastChecked = prefs.getLong(res.getString(R.string.pref_lastchecked), 0);
		long now = (System.currentTimeMillis() / 1000);
		long passed = now - lastChecked;
		
		boolean shouldCheck = false;
		if(frequency.equals(res.getString(R.string.list_update_daily))) {
			shouldCheck = (passed > 60 * 60 * 24);
		} else if(frequency.equals(res.getString(R.string.list_update_weekly))) {
			shouldCheck = (passed > 60 * 60 * 24 * 7);
		}
		
		// place version information in user-agent string to be used later
		this.userAgent = String.format("%s/%s (%d, freq=%s)", packageName, versionName, versionCode, frequency);

		if(shouldCheck) {
			// spawn thread to check for update
			new Thread(this).start();
			
			// update our last-checked time
			Editor editor = prefs.edit();
			editor.putLong(res.getString(R.string.pref_lastchecked), now);
			editor.commit();
			
		}

	}

	public void run() {
		try {
			// fetch and parse the version update information as json
			// pass information off to handler to create
			JSONObject json = new JSONObject(UpdateHelper.getUrl(UPDATE_URL, userAgent));
			Message.obtain(versionHandler, -1, json).sendToTarget();
			
		} catch(Exception e) {
			Log.e(TAG, "Problem while fetching/parsing update response", e);

		}
	}
	

	/**
	 * Handler that will parse the JSON response and show dialog to user if an
	 * update is available.
	 */
	public Handler versionHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {

			// make sure we are being passed a real json object
			if(!(msg.obj instanceof JSONObject)) return;
			JSONObject json = (JSONObject)msg.obj;

			// pull out version and target information from response
			final int versionCode = json.optInt("versionCode");
			final String features = json.optString("features");
			final String target = "market://" + json.optString("target");
			
			// skip if we're already good enough
			if(versionCode <= UpdateHelper.this.versionCode) return;
			
			// build dialog to prompt user about updating
			new AlertDialog.Builder(context)
				.setTitle(R.string.upgrade)
				.setMessage(features)
				.setPositiveButton(R.string.upgrade_pos, new DialogInterface.OnClickListener() {
	                public void onClick(DialogInterface dialog, int which) {
						Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
						context.startActivity(intent);
	                }
	            })
	            .setNegativeButton(R.string.upgrade_neg, null).create().show();
			
		}
		

	};

	/**
	 * Read contents of a URL and return as a String. Handles any server
	 * downtime with a 6-second timeout.
	 */
	public static String getUrl(String tryUrl, String userAgent) throws Exception {
		
		URL url = new URL(tryUrl);
		URLConnection connection = url.openConnection();
		connection.setConnectTimeout(6000);
		connection.setReadTimeout(6000);
		connection.setRequestProperty("User-Agent", userAgent);
		connection.connect();
		
		InputStream is = connection.getInputStream();
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		
		int bytesRead;
		byte[] buffer = new byte[1024];
		while ((bytesRead = is.read(buffer)) != -1) {
			os.write(buffer, 0, bytesRead);
		}

		os.flush();
		os.close();
		is.close();

		return new String(os.toByteArray());
		
	}

	
}
