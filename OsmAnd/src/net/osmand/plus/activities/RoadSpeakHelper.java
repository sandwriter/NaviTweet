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
	private final static Log log = LogUtil.getLog(RoadSpeakHelper.class);

	public RoadSpeakHelper(Context ctx) {
		this.ctx = ctx;
		settings = ((OsmandApplication)ctx.getApplicationContext()).getSettings();
	}

	public int getOnlineMemberCount() {
		// TODO Auto-generated method stub
		return 0;
	}

	public ArrayList<String> getGroupListName() {
		ArrayList<String> groupListName = new ArrayList<String>();
		// dummy
		groupListName.add("group name");
		return groupListName;
	}

}
