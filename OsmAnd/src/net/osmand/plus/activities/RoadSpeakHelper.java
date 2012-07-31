package net.osmand.plus.activities;

import java.util.ArrayList;

import net.osmand.LogUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings;

import org.apache.commons.logging.Log;

import android.content.Context;

public class RoadSpeakHelper {
	
	protected Context ctx;
	private OsmandSettings settings;
	private long lastTimeUpdated;
	private final static Log log = LogUtil.getLog(RoadSpeakHelper.class);
	
	private int onlineMemberCount = 0;
	private ArrayList<String> groupListName;

	public RoadSpeakHelper(Context ctx) {
		this.ctx = ctx;
		settings = ((OsmandApplication)ctx.getApplicationContext()).getSettings();
	}
	
	public boolean isKeepLoggedInEnabled(){
		return settings.ROADSPEAK_KEEP_LOGGED_IN.get();
	}
	
	public synchronized void setOnlineMemberCount(int count){
		this.onlineMemberCount = count;
	}

	public synchronized void setGroupListName(ArrayList<String> groupListName) {
		this.groupListName = groupListName;
	}
	
	public synchronized int getOnlineMemberCount() {
		return onlineMemberCount;
	}

	public synchronized ArrayList<String> getGroupListName() {
		return groupListName;
	}

}
