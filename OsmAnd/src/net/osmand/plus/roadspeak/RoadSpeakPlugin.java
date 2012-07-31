package net.osmand.plus.roadspeak;

import java.util.ArrayList;
import java.util.EnumSet;

import net.osmand.LogUtil;
import net.osmand.OsmAndFormatter;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.activities.ApplicationMode;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.activities.SettingsActivity;
import net.osmand.plus.views.MapInfoControl;
import net.osmand.plus.views.MapInfoLayer;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.TextInfoControl;

import org.apache.commons.logging.Log;

import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.DialogInterface;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.preference.EditTextPreference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListAdapter;
import android.widget.TextView;

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
	private static final Log log = LogUtil.getLog(RoadSpeakPlugin.class);
	private MapInfoControl roadspeakControl;

	public RoadSpeakPlugin(OsmandApplication app) {
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
		MapInfoLayer layer = activity.getMapLayers().getMapInfoLayer();
		roadspeakControl = createRoadSpeakControl(activity,
				layer.getPaintText(), layer.getPaintSubText());
		layer.getMapInfoControls().registerSideWidget(roadspeakControl,
				R.drawable.roadspeak_logged_in_big, R.string.map_widget_roadspeak,
				"roadspeak", false,
				EnumSet.of(ApplicationMode.CAR, ApplicationMode.PEDESTRIAN),
				EnumSet.noneOf(ApplicationMode.class), 25);
		layer.recreateControls();
	}

	@Override
	public void updateLayers(OsmandMapTileView mapView, MapActivity activity) {
		if (roadspeakControl == null) {
			registerLayers(activity);
		}
	}

	private MapInfoControl createRoadSpeakControl(final MapActivity map,
			Paint paintText, Paint paintSubText) {
		final Drawable roadspeakBig = map.getResources().getDrawable(
				R.drawable.roadspeak_logged_in_big);
		final Drawable roadspeakInactive = map.getResources().getDrawable(
				R.drawable.monitoring_rec_inactive);
		roadspeakControl = new TextInfoControl(map, 0, paintText, paintSubText) {
			@Override
			public boolean updateInfo() {
				boolean visible = true;
				String txt = null;
				String subtxt = null;
				Drawable d = roadspeakInactive;
				if (settings.ROADSPEAK_KEEP_LOGGED_IN.get()) {
					long count = app.getRoadSpeakHelper().getOnlineMemberCount();
					txt = OsmAndFormatter.getFormattedOnlineMemberCount(count,
							map);
					subtxt = app.getString(R.string.people);
					d = roadspeakBig;
				}
				setText(txt, subtxt);
				setImageDrawable(d);
				updateVisibility(visible);
				return true;
			}
		};
		roadspeakControl.updateInfo();

		roadspeakControl.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				openGroupListDialog();
			}

			private void openGroupListDialog() {
				final ArrayList<Object> list = new ArrayList<Object>();
				list.addAll(app.getRoadSpeakHelper().getGroupListName());

				Builder b = new AlertDialog.Builder(map);
				ListAdapter listAdapter = new ArrayAdapter<Object>(map,
						R.layout.layers_list_activity_item, R.id.title, list) {

					@Override
					public View getView(int position, View convertView,
							ViewGroup parent) {
						View v = convertView;
						if (v == null) {
							v = map.getLayoutInflater().inflate(
									R.layout.layers_list_activity_item, null);
						}
						final TextView tv = (TextView) v
								.findViewById(R.id.title);
						final CheckBox ch = (CheckBox) v
								.findViewById(R.id.check_item);
						Object o = list.get(position);
						tv.setText(o.toString());
						tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
						tv.setPadding(
								(int) (5 * MapInfoLayer.scaleCoefficient), 0,
								0, 0);
						ch.setVisibility(View.INVISIBLE);

						return v;
					}

				};
				
				b.setAdapter(listAdapter, new DialogInterface.OnClickListener() {
					
					@Override
					public void onClick(DialogInterface dialog, int which) {
											
					}
				});

				final AlertDialog dlg = b.create();
				dlg.setCanceledOnTouchOutside(true);
				dlg.show();

			}
		});

		return roadspeakControl;
	}

	@Override
	public void settingsActivityCreate(SettingsActivity activity,
			PreferenceScreen screen) {
		PreferenceScreen general = (PreferenceScreen) screen
				.findPreference(SettingsActivity.SCREEN_ID_GENERAL_SETTINGS);
		PreferenceCategory cat = new PreferenceCategory(app);
		cat.setTitle(R.string.roadspeak_settings);
		general.addPreference(cat);

		cat.addPreference(activity.createEditTextPreference(
				settings.ROADSPEAK_USER_NAME, R.string.roadspeak_user_name,
				R.string.roadspeak_user_name_description));
		EditTextPreference pwd = activity.createEditTextPreference(
				settings.ROADSPEAK_USER_PASSWORD,
				R.string.roadspeak_user_password,
				R.string.roadspeak_user_password_description);
		pwd.getEditText().setInputType(
				InputType.TYPE_CLASS_TEXT
						| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		cat.addPreference(pwd);
		cat.addPreference(activity.createCheckBoxPreference(
				settings.ROADSPEAK_KEEP_LOGGED_IN,
				R.string.roadspeak_keep_logged_in,
				R.string.roadspeak_keep_logged_in_summary));
		cat.addPreference(activity.createEditTextPreference(settings.ROADSPEAK_UPDATE_URL, R.string.roadspeak_update_url, R.string.roadspeak_update_url_description));
		cat.addPreference(activity.createTimeListPreference(settings.ROADSPEAK_INTERVAL, new int[]{}, new int[]{5, 10, 15, 30, 60, 120}, 1, R.string.roadspeak_interval, R.string.roadspeak_interval_description));
	}

}
