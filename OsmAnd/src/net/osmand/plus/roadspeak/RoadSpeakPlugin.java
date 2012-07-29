package net.osmand.plus.roadspeak;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.activities.SettingsActivity;
import android.preference.EditTextPreference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.text.InputType;

/*
 * 
 * This plugin allow user to dispatch traffic information in the vehicular social network
 * 
 * @author Wenjie Sha
 * 
 */

public class RoadSpeakPlugin extends OsmandPlugin {
	private static final String ID = "osmand.roadspeak";
	private OsmandApplication app;
	private OsmandSettings settings;
	
	public RoadSpeakPlugin(OsmandApplication app){
		this.app = app;
	}

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getDescription() {
		return app.getString(R.string.osmand_roadspeak_plugin_description);
	}

	@Override
	public String getName() {
		return app.getString(R.string.osmand_roadspeak_plugin_name);
	}

	@Override
	public boolean init(OsmandApplication app) {
		this.settings = app.getSettings();
		return true;
	}

	@Override
	public void registerLayers(MapActivity activity) {

	}

	@Override
	public void settingsActivityCreate(SettingsActivity activity,
			PreferenceScreen screen) {
		PreferenceScreen general = (PreferenceScreen) screen.findPreference(SettingsActivity.SCREEN_ID_GENERAL_SETTINGS);
		PreferenceCategory cat = new PreferenceCategory(app);
		cat.setTitle(R.string.roadspeak_settings);
		general.addPreference(cat);
		
		cat.addPreference(activity.createEditTextPreference(settings.ROADSPEAK_USER_NAME, R.string.roadspeak_user_name, R.string.roadspeak_user_name_description));
		EditTextPreference pwd = activity.createEditTextPreference(settings.ROADSPEAK_USER_PASSWORD, R.string.roadspeak_user_password, R.string.roadspeak_user_password_description);
		pwd.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
		cat.addPreference(pwd);		
	}
	
	

}
