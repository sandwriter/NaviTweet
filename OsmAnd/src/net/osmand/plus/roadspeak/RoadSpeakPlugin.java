package net.osmand.plus.roadspeak;

import gnu.trove.map.hash.TLongObjectHashMap;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.osmand.LogUtil;
import net.osmand.OsmAndFormatter;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.RouteDataObject;
import net.osmand.osm.LatLon;
import net.osmand.osm.MapUtils;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.ResourceManager;
import net.osmand.plus.activities.ApplicationMode;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.activities.RoadSpeakHelper.DataSourceObject;
import net.osmand.plus.activities.RoadSpeakHelper.MessageObject;
import net.osmand.plus.activities.SettingsActivity;
import net.osmand.plus.render.NativeOsmandLibrary;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.views.MapInfoControl;
import net.osmand.plus.views.MapInfoLayer;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.TextInfoControl;
import net.osmand.plus.voice.CommandPlayer;
import net.osmand.plus.voice.TTSCommandPlayerImpl;
import net.osmand.router.BinaryRoutePlanner;
import net.osmand.router.BinaryRoutePlanner.RouteSegment;
import net.osmand.router.GeneralRouter.GeneralRouterProfile;
import net.osmand.router.RoutingConfiguration;
import net.osmand.router.RoutingContext;
import net.osmand.router.RoutingContext.RoutingTile;

import org.apache.commons.logging.Log;
import org.xml.sax.SAXException;

import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.EditTextPreference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ListAdapter;
import android.widget.TextView;
import android.widget.Toast;

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
	private MapInfoControl roadspeakGroupControl;
	private MapInfoControl roadspeakFriendControl;
	private MapInfoControl roadspeakFetchControl;
	private Handler handler = new Handler();

	private final String ROADSPEAK_FETCH_MESSAGE_TIMER_ID = "roadspeak_fetch_message";
	private SimpleTimer roadspeakFetchMessageTimer = new SimpleTimer(
			ROADSPEAK_FETCH_MESSAGE_TIMER_ID);
	private final String ROADSPEAK_UPDATE_LOCATION_TIMER_ID = "roadspeak_update_location";
	private SimpleTimer updateLocationTimer = null;
	private MediaPlayer mPlayer = null;
	private String digestDirPath = null;

	private MapActivity map;
	private RoutingHelper routingHelper;
	private MessageRouteHelper messageRouteHelper;
	private TTSCommandPlayerImpl ttsPlayer = null;
	public static Decision decision = null;

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
		prepareTmpStorage();
		return true;
	}

	private void prepareTmpStorage() {
		this.digestDirPath = Environment.getExternalStorageDirectory()
				.getAbsolutePath() + "/digest";
		File digestDir = new File(digestDirPath);
		if (digestDir.exists()) {
			File[] files = digestDir.listFiles();
			for (File f : files) {
				f.delete();
			}
		} else {
			digestDir.mkdir();
		}
	}

	@Override
	public void registerLayers(MapActivity activity) {
		MapInfoLayer layer = activity.getMapLayers().getMapInfoLayer();
		roadspeakGroupControl = createRoadSpeakGroupControl(activity,
				layer.getPaintText(), layer.getPaintSubText());
		layer.getMapInfoControls().registerSideWidget(roadspeakGroupControl,
				R.drawable.roadspeak_logged_in_big,
				R.string.map_widget_roadspeak_group, "roadspeak_group", false,
				EnumSet.of(ApplicationMode.CAR, ApplicationMode.PEDESTRIAN),
				EnumSet.noneOf(ApplicationMode.class), 25);
		roadspeakFriendControl = createRoadSpeakFriendControl(activity,
				layer.getPaintText(), layer.getPaintSubText());
		layer.getMapInfoControls().registerSideWidget(roadspeakFriendControl,
				R.drawable.roadspeak_logged_in_big,
				R.string.map_widget_roadspeak_friend, "roadspeak_friend",
				false,
				EnumSet.of(ApplicationMode.CAR, ApplicationMode.PEDESTRIAN),
				EnumSet.noneOf(ApplicationMode.class), 26);

		// roadspeakFriendControl = createRoadSpeak

		// plugin to fetch messages
		roadspeakFetchControl = createRoadSpeakFetchControl(activity,
				layer.getPaintText(), layer.getPaintSubText());
		activity.getRoadSpeakHelper().setRoadspeakPlugin(this);

		layer.getMapInfoControls().registerSideWidget(roadspeakFetchControl,
				R.drawable.roadspeak_fetch_message_big,
				R.string.map_widget_roadspeak_fetchmessage,
				"roadspeak_fetchmessage", false,
				EnumSet.of(ApplicationMode.CAR, ApplicationMode.PEDESTRIAN),
				EnumSet.noneOf(ApplicationMode.class), 28);
		layer.recreateControls();
	}

	private MapInfoControl createRoadSpeakFetchControl(MapActivity map,
			Paint paintText, Paint paintSubText) {
		final Drawable roadspeakFetchMessageBig = map.getResources()
				.getDrawable(R.drawable.roadspeak_fetch_message_big);
		final Drawable roadspeakFetchMessageInactive = map.getResources()
				.getDrawable(R.drawable.monitoring_rec_inactive);
		final MapActivity mapActivity = map;
		roadspeakFetchControl = new TextInfoControl(map, 0, paintText,
				paintSubText) {
			@Override
			public boolean updateInfo() {
				boolean visible = true;
				String txt = null;
				String subtxt = null;
				Drawable d = roadspeakFetchMessageInactive;
				if (settings.ROADSPEAK_KEEP_LOGGED_IN.get()
						&& mapActivity.getRoutingHelper().isFollowingMode()) {
					int seconds = getRoadSpeakFetchControlTimer();
					txt = OsmAndFormatter.getFormattedTime(seconds);
					d = roadspeakFetchMessageBig;
				}
				setText(txt, subtxt);
				setImageDrawable(d);
				updateVisibility(visible);
				return true;
			}
		};
		roadspeakFetchControl.updateInfo();

		roadspeakFetchControl.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				fetchAndPlayMessage();
			}
		});
		return roadspeakFetchControl;
	}

	private int getRoadSpeakFetchControlTimer() {
		return roadspeakFetchMessageTimer.getSeconds();
	}

	public void fetchAndPlayMessage() {
		if (routingHelper == null) {
			log.error("Routing Helper is null");
		}
		if (messageRouteHelper != null) {
			messageRouteHelper.stopCalculation();
			messageRouteHelper = null;
		}
		if (messageRouteHelper == null) {
			messageRouteHelper = new MessageRouteHelper(
					map.getRoadSpeakHelper().finalLocation,
					map.getRoadSpeakHelper().currentLocation, map
							.getRoadSpeakHelper().cloneDataSourceObjectList());
			synchronized (app) {
				messageRouteHelper.route();
			}
			log.debug("exit the message router");
		}
	}

	public void resetRoadSpeakFetchMessageTimer() {
		final int seconds = settings.ROADSPEAK_DIGEST_INTERVAL.get();
		roadspeakFetchMessageTimer.reset(seconds, 1, true);
	}

	public void startRoadSpeakFetchMessageTimer() {
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				fetchAndPlayMessage();
			}
		});
		thread.start();
		roadspeakFetchMessageTimer.start();
	}

	public void pauseRoadSpeakFetchMessageTimer() {
		roadspeakFetchMessageTimer.pause();
	}

	public void onCountDownFinished(String id) {
		pauseRoadSpeakFetchMessageTimer();
		resetRoadSpeakFetchMessageTimer();
		startRoadSpeakFetchMessageTimer();
	}

	public void onCountDown(String id) {
		roadspeakFetchControl.updateInfo();
	}

	@Override
	public void updateLayers(OsmandMapTileView mapView, MapActivity activity) {
		if (roadspeakGroupControl == null || roadspeakFetchControl == null) {
			registerLayers(activity);
		}
	}

	private MapInfoControl createRoadSpeakFriendControl(final MapActivity map,
			Paint paintText, Paint paintSubText) {
		final Drawable roadspeakBig = map.getResources().getDrawable(
				R.drawable.roadspeak_friends_logged_in);
		final Drawable roadspeakInactive = map.getResources().getDrawable(
				R.drawable.monitoring_rec_inactive);
		roadspeakFriendControl = new TextInfoControl(map, 0, paintText,
				paintSubText) {

			@Override
			public boolean updateInfo() {
				boolean visible = true;
				String txt = null;
				String subtxt = null;
				Drawable d = roadspeakInactive;
				if (settings.ROADSPEAK_KEEP_LOGGED_IN.get()) {
					long count = app.getRoadSpeakHelper().getFriendListName()
							.size();
					txt = OsmAndFormatter.getFormattedFriendListCount(count,
							map);
					subtxt = app.getString(R.string.friend);
					d = roadspeakBig;
				}
				setText(txt, subtxt);
				setImageDrawable(d);
				updateVisibility(visible);
				return true;
			}
		};
		roadspeakFriendControl.updateInfo();
		roadspeakFriendControl.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				openFriendListDialog();
			}

			private void openFriendListDialog() {
				final ArrayList<Object> list = new ArrayList<Object>();
				list.addAll(app.getRoadSpeakHelper().getFriendListName());

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

				b.setAdapter(listAdapter,
						new DialogInterface.OnClickListener() {

							@Override
							public void onClick(DialogInterface dialog,
									int which) {

							}
						});

				final AlertDialog dlg = b.create();
				dlg.setCanceledOnTouchOutside(true);
				dlg.show();

			}

		});

		return roadspeakFriendControl;
	}

	private MapInfoControl createRoadSpeakGroupControl(final MapActivity map,
			Paint paintText, Paint paintSubText) {
		final Drawable roadspeakBig = map.getResources().getDrawable(
				R.drawable.roadspeak_groups_logged_in);
		final Drawable roadspeakInactive = map.getResources().getDrawable(
				R.drawable.monitoring_rec_inactive);
		roadspeakGroupControl = new TextInfoControl(map, 0, paintText,
				paintSubText) {
			@Override
			public boolean updateInfo() {
				boolean visible = true;
				String txt = null;
				String subtxt = null;
				Drawable d = roadspeakInactive;
				if (settings.ROADSPEAK_KEEP_LOGGED_IN.get()) {
					long count = app.getRoadSpeakHelper().getGroupListName()
							.size();
					txt = OsmAndFormatter
							.getFormattedGroupListCount(count, map);
					subtxt = app.getString(R.string.group);
					d = roadspeakBig;
				}
				setText(txt, subtxt);
				setImageDrawable(d);
				updateVisibility(visible);
				return true;
			}
		};
		roadspeakGroupControl.updateInfo();

		roadspeakGroupControl.setOnClickListener(new View.OnClickListener() {

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

				b.setAdapter(listAdapter,
						new DialogInterface.OnClickListener() {

							@Override
							public void onClick(DialogInterface dialog,
									int which) {

							}
						});

				final AlertDialog dlg = b.create();
				dlg.setCanceledOnTouchOutside(true);
				dlg.show();

			}
		});

		return roadspeakGroupControl;
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
		cat.addPreference(activity.createEditTextPreference(
				settings.ROADSPEAK_UPDATE_URL, R.string.roadspeak_update_url,
				R.string.roadspeak_update_url_description));
		cat.addPreference(activity.createEditTextPreference(
				settings.ROADSPEAK_UPDATE_ENVIRONMENT_URL,
				R.string.roadspeak_update_environment_url,
				R.string.roadspeak_update_environment_url_description));
		cat.addPreference(activity.createEditTextPreference(
				settings.ROADSPEAK_UPLOAD_URL, R.string.roadspeak_upload_url,
				R.string.roadspeak_upload_url_description));
		cat.addPreference(activity.createEditTextPreference(
				settings.ROADSPEAK_DOWNLOAD_URL,
				R.string.roadspeak_download_url,
				R.string.roadspeak_download_url_description));
		cat.addPreference(activity.createEditTextPreference(
				settings.ROADSPEAK_DOWNLOAD_MESSAGE_URL,
				R.string.roadspeak_digest_message_download_url,
				R.string.roadspeak_digest_message_download_url_description));
		cat.addPreference(activity.createTimeListPreference(
				settings.ROADSPEAK_DIGEST_INTERVAL, new int[] {}, new int[] {
						5, 10, 15, 30, 60, 120 }, 1,
				R.string.roadspeak_digest_interval,
				R.string.roadspeak_digest_interval_description));
		cat.addPreference(activity.createTimeListPreference(
				settings.ROADSPEAK_UPDATE_INTERVAL, new int[] { 5, 10, 30 },
				new int[] { 1, 5, 10 }, 1, R.string.roadspeak_update_interval,
				R.string.roadspeak_update_interval_description));
		cat.addPreference(activity.createListPreference(
				settings.ROADSPEAK_DIGEST_NUMBER,
				new String[] { app.getString(R.string.roadspeak_one_message),
						app.getString(R.string.roadspeak_two_message),
						app.getString(R.string.roadspeak_three_message),
						app.getString(R.string.roadspeak_four_message),
						app.getString(R.string.roadspeak_all_message) },
				new Integer[] { Digest.ONE_MESSAGE, Digest.TWO_MESSAGE,
						Digest.THREE_MESSAGE, Digest.FOUR_MESSAGE,
						Digest.ALL_MESSAGE }, R.string.roadspeak_digest_number,
				R.string.roadspeak_digest_number_description));
		cat.addPreference(activity.createListPreference(
				settings.ROADSPEAK_DIGEST_ALGORITHM,
				new String[] {
						app.getString(R.string.roadspeak_order_by_speed),
						app.getString(R.string.roadspeak_order_by_distance_to_src),
						app.getString(R.string.roadspeak_order_by_distance_to_end),
						app.getString(R.string.roadspeak_order_by_mix) },
				new Integer[] { Digest.ORDER_BY_SPEED,
						Digest.ORDER_BY_DISTANCE_FROM_SRC,
						Digest.ORDER_BY_DISTANCE_TO_DEST, Digest.ORDER_BY_MIX },
				R.string.roadspeak_digest_algorithm,
				R.string.roadspeak_digest_algorithm_description));
	}

	@Override
	public void mapActivityResume(MapActivity activity) {
		this.map = activity;
		this.routingHelper = map.getRoutingHelper();
		if (updateLocationTimer == null) {
			updateLocationTimer = new SimpleTimer(
					ROADSPEAK_UPDATE_LOCATION_TIMER_ID) {
				@Override
				public void onCountDown() {
					Location loc = map.getLastKnownLocation();
					LatLon finalLoc = map.getRoutingHelper().getFinalLocation();
					map.getRoadSpeakHelper().updateEnvironment(loc, finalLoc,
							MapActivity.ACCURACY_FOR_GPX_AND_ROUTING);
				}
			};
			updateLocationTimer.reset(0,
					settings.ROADSPEAK_UPDATE_INTERVAL.get(), false);
		}
		updateLocationTimer.start();
	}

	@Override
	public void mapActivityPause(MapActivity activity) {
		super.mapActivityPause(activity);
		updateLocationTimer.pause();
	}

	@Override
	public void mapActivityDestroy(MapActivity activity) {
		super.mapActivityDestroy(activity);
		map.getRoadSpeakHelper().disableRoadSpeakMessage();
	}

	private class SimpleTimer {
		public String id;
		private int seconds;
		private int interval;
		private boolean hasLimit = true;
		private ScheduledExecutorService scheduler = Executors
				.newScheduledThreadPool(5);
		private ScheduledFuture schedulerHandler;

		public SimpleTimer(String id) {
			this.id = id;
		}

		public synchronized int getSeconds() {
			return seconds;
		}

		public synchronized boolean countdown() {
			if (!hasLimit) {
				handler.post(new Runnable() {
					@Override
					public void run() {
						SimpleTimer.this.onCountDown();
					}
				});
				return true;
			}
			if (this.seconds > 0) {
				this.seconds -= interval;
				handler.post(new Runnable() {
					@Override
					public void run() {
						SimpleTimer.this.onCountDown();
					}
				});
				return true;
			}
			return false;
		}

		public void reset(int seconds, int interval, boolean hasLimit) {
			this.seconds = seconds;
			this.interval = interval;
			this.hasLimit = hasLimit;
		}

		public void start() {
			schedulerHandler = scheduler.scheduleAtFixedRate(new Runnable() {
				@Override
				public void run() {
					if (!countdown()) {
						handler.post(new Runnable() {
							@Override
							public void run() {
								SimpleTimer.this.onCountDownFinish();
							}
						});
						schedulerHandler.cancel(true);
					}
				}

			}, interval, interval, TimeUnit.SECONDS);
		}

		public void pause() {
			if (schedulerHandler != null) {
				schedulerHandler.cancel(true);
			}
		}

		public void onCountDown() {
			RoadSpeakPlugin.this.onCountDown(SimpleTimer.this.id);
		}

		public void onCountDownFinish() {
			RoadSpeakPlugin.this.onCountDownFinished(SimpleTimer.this.id);
		}
	}

	private class MessageRouteHelper {
		private LatLon finalLocation = null;
		private Location currentLocation = null;
		private ArrayList<DataSourceObject> dataSourceObjectList;

		public MessageRouteHelper(LatLon finalLocation,
				Location currentLocation,
				ArrayList<DataSourceObject> cloneDataSourceObjectList) {
			this.finalLocation = finalLocation;
			this.currentLocation = currentLocation;
			this.dataSourceObjectList = cloneDataSourceObjectList;
		}

		public void route() {
			final PriorityQueue<DataSourceObject> target;
			try {
				RoutingHelper routingHelper = map.getRoutingHelper();
				target = routeQuery(currentLocation, finalLocation,
						dataSourceObjectList);
				log.debug("routing finished");

				if (target != null) {
					handler.post(new Runnable() {
						@Override
						public void run() {
							Toast.makeText(map, "Playing digest....",
									Toast.LENGTH_SHORT).show();
						}
					});
					downloadAndPlayDigest(target);
					log.debug("message posted");
				}
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}

		}

		private PriorityQueue<DataSourceObject> routeQuery(
				Location currentLocation, LatLon finalLocation,
				ArrayList<DataSourceObject> dataSourceObjectList)
				throws Exception {
			TLongObjectHashMap<DataSourceObject> dataSourceObjects = new TLongObjectHashMap<DataSourceObject>();

			Comparator<DataSourceObject> dataComparatorBySpeed = new Comparator<DataSourceObject>() {
				@Override
				public int compare(DataSourceObject o1, DataSourceObject o2) {
					return Float.compare(o1.getSpeed(), o2.getSpeed());
				}
			};

			Comparator<DataSourceObject> dataComparatorByDistanceFromSource = new Comparator<DataSourceObject>() {
				@Override
				public int compare(DataSourceObject o1, DataSourceObject o2) {
					return Double.compare(o1.getDistanceFromStart(),
							o2.getDistanceFromStart());
				}
			};

			Comparator<DataSourceObject> dataComparatorByDistanceToEnd = new Comparator<DataSourceObject>() {
				@Override
				public int compare(DataSourceObject o1, DataSourceObject o2) {
					return Double.compare(o1.getDistanceToEnd(),
							o2.getDistanceToEnd());
				}
			};

			Comparator<DataSourceObject> dataComparatorByMixStrategy = new Comparator<DataSourceObject>() {
				@Override
				public int compare(DataSourceObject o1, DataSourceObject o2) {
					double speedFrac1 = o1.getSpeed() / o1.getMaxAllowedSpeed();
					double speedFrac2 = o2.getSpeed() / o2.getMaxAllowedSpeed();
					if (speedFrac1 < Digest.THRESHOLD
							&& speedFrac2 < Digest.THRESHOLD) {
						return Double.compare(o1.getDistanceFromStart(),
								o2.getDistanceFromStart());
					} else if (speedFrac1 > Digest.THRESHOLD
							&& speedFrac2 > Digest.THRESHOLD) {
						return 0;
					} else {
						return Double.compare(speedFrac1, speedFrac2);
					}
				}
			};

			PriorityQueue<DataSourceObject> result = null;
			switch (settings.ROADSPEAK_DIGEST_ALGORITHM.get()) {
			case Digest.ORDER_BY_SPEED: {
				result = new PriorityQueue<DataSourceObject>(20,
						dataComparatorBySpeed);
				break;
			}
			case Digest.ORDER_BY_DISTANCE_FROM_SRC: {
				result = new PriorityQueue<DataSourceObject>(20,
						dataComparatorByDistanceFromSource);
				break;
			}
			case Digest.ORDER_BY_DISTANCE_TO_DEST: {
				result = new PriorityQueue<DataSourceObject>(20,
						dataComparatorByDistanceToEnd);
				break;
			}
			case Digest.ORDER_BY_MIX: {
				result = new PriorityQueue<DataSourceObject>(20,
						dataComparatorByMixStrategy);
				break;
			}
			default: {
				result = new PriorityQueue<DataSourceObject>(20,
						dataComparatorByMixStrategy);
			}
			}

			ApplicationMode mode = routingHelper.getAppMode();
			if (mode != ApplicationMode.CAR) {
				return result;
			}
			BinaryMapIndexReader[] files = app.getResourceManager()
					.getRoutingMapFiles();
			BinaryRoutePlanner router = new BinaryRoutePlanner(
					NativeOsmandLibrary.getLoadedLibrary(), files);
			File routingXml = app.getSettings().extendOsmandPath(
					ResourceManager.ROUTING_XML);
			RoutingConfiguration.Builder config;
			if (routingXml.exists() && routingXml.canRead()) {
				try {
					config = RoutingConfiguration
							.parseFromInputStream(new FileInputStream(
									routingXml));
				} catch (SAXException e) {
					throw new IllegalStateException(e);
				}
			} else {
				config = RoutingConfiguration.getDefault();
			}

			GeneralRouterProfile p = GeneralRouterProfile.CAR;
			final RoutingContext ctx = new RoutingContext(config.build(p.name()
					.toLowerCase(), true,
					currentLocation.hasBearing() ? currentLocation.getBearing()
							/ 180d * Math.PI : null));
			prepareDataSourceObject(dataSourceObjectList, dataSourceObjects,
					router, ctx);

			RouteSegment start = router.findRouteSegment(
					currentLocation.getLatitude(),
					currentLocation.getLongitude(), ctx);
			if (start == null) {
				throw new Exception("start is null");
			}
			// logRouteSegment("start", start);
			checkDataSourceObject(start.getRoad(), start.getSegmentStart(), 0,
					MapUtils.getDistance(currentLocation.getLatitude(),
							currentLocation.getLongitude(),
							finalLocation.getLatitude(),
							finalLocation.getLongitude()), dataSourceObjects,
					result);
			RouteSegment end = router.findRouteSegment(
					finalLocation.getLatitude(), finalLocation.getLongitude(),
					ctx);
			if (end == null) {
				throw new Exception("end is null");
			}
			boolean found = checkFoundRoute(start.getRoad().id,
					start.getSegmentStart(), end);
			if (found) {
				return result;
			}

			ctx.timeToLoad = 0;
			ctx.visitedSegments = 0;
			ctx.timeToCalculate = System.nanoTime();
			if (ctx.config.initialDirection != null) {
				ctx.firstRoadId = (start.getRoad().id << BinaryRoutePlanner.ROUTE_POINTS)
						+ start.getSegmentStart();
				double plusDir = start.getRoad().directionRoute(
						start.segmentStart, true);
				double diff = plusDir - ctx.config.initialDirection;
				if (Math.abs(MapUtils.alignAngleDifference(diff)) <= Math.PI / 3) {
					ctx.firstRoadDirection = 1;
				} else if (Math.abs(MapUtils.alignAngleDifference(diff
						- Math.PI)) <= Math.PI / 3) {
					ctx.firstRoadDirection = -1;
				}
			}

			Comparator<RouteSegment> segmentsComparator = new Comparator<RouteSegment>() {
				@Override
				public int compare(RouteSegment o1, RouteSegment o2) {
					return ctx.roadPriorityComparator(o1.distanceFromStart,
							o1.distanceToEnd, o2.distanceFromStart,
							o2.distanceToEnd);
				}
			};

			PriorityQueue<RouteSegment> graphSegments = new PriorityQueue<RouteSegment>(
					50, segmentsComparator);
			TLongObjectHashMap<RouteSegment> visitedSegments = new TLongObjectHashMap<RouteSegment>();

			int targetEndX = end.road.getPoint31XTile(end.segmentStart);
			int targetEndY = end.road.getPoint31YTile(end.segmentStart);
			int startX = start.road.getPoint31XTile(start.segmentStart);
			int startY = start.road.getPoint31YTile(start.segmentStart);
			float estimatedDistance = (float) router.h(ctx, targetEndX,
					targetEndY, startX, startY);
			start.distanceToEnd = estimatedDistance;

			graphSegments.add(start);
			while (!graphSegments.isEmpty()) {
				RouteSegment segment = graphSegments.poll();
				// logRouteSegment("poll", segment);
				ctx.visitedSegments++;
				final RouteDataObject road = segment.road;
				final int middle = segment.segmentStart;
				double obstaclePlusTime = 0;
				double obstacleMinusTime = 0;

				long nt = (road.getId() << BinaryRoutePlanner.ROUTE_POINTS)
						+ middle;

				visitedSegments.put(nt, segment);

				int oneway = ctx.getRouter().isOneWay(road);
				boolean minusAllowed;
				boolean plusAllowed;
				if (ctx.firstRoadId == nt) {
					minusAllowed = ctx.firstRoadDirection <= 0;
					plusAllowed = ctx.firstRoadDirection >= 0;
				} else {
					minusAllowed = oneway <= 0;
					plusAllowed = oneway >= 0;
				}

				int d = plusAllowed ? 1 : -1;
				if (segment.parentRoute != null) {
					if (plusAllowed
							&& middle < segment.getRoad().getPointsLength() - 1) {
						obstaclePlusTime = ctx.getRouter().calculateTurnTime(
								segment,
								segment.getRoad().getPointsLength() - 1,
								segment.parentRoute, segment.parentSegmentEnd);
					}
					if (minusAllowed && middle > 0) {
						obstacleMinusTime = ctx.getRouter().calculateTurnTime(
								segment, 0, segment.parentRoute,
								segment.parentSegmentEnd);
					}
				}

				double posSegmentDist = 0;
				double negSegmentDist = 0;
				while (minusAllowed || plusAllowed) {
					int segmentEnd = middle + d;
					boolean positive = d > 0;
					if (!minusAllowed && d > 0) {
						d++;
					} else if (!plusAllowed && d < 0) {
						d--;
					} else {
						if (d <= 0) {
							d = -d + 1;
						} else {
							d = -d;
						}
					}
					if (segmentEnd < 0) {
						minusAllowed = false;
						continue;
					}
					if (segmentEnd >= road.getPointsLength()) {
						plusAllowed = false;
						continue;
					}

					int x = road.getPoint31XTile(segmentEnd);
					int y = road.getPoint31YTile(segmentEnd);
					RoutingTile tile = router.loadRoutes(ctx, x, y);
					if (positive) {
						posSegmentDist += BinaryRoutePlanner.squareRootDist(x,
								y, road.getPoint31XTile(segmentEnd - 1),
								road.getPoint31YTile(segmentEnd - 1));
					} else {
						negSegmentDist += BinaryRoutePlanner.squareRootDist(x,
								y, road.getPoint31XTile(segmentEnd + 1),
								road.getPoint31YTile(segmentEnd + 1));
					}
					double obstacle = ctx.getRouter().defineObstacle(road,
							segmentEnd);
					if (positive) {
						if (obstacle < 0) {
							plusAllowed = false;
							continue;
						}
						obstaclePlusTime += obstacle;
					} else {
						if (obstacle < 0) {
							minusAllowed = false;
							continue;
						}
						obstacleMinusTime += obstacle;
					}

					double priority = ctx.getRouter().defineSpeedPriority(road);
					double speed = ctx.getRouter().defineSpeed(road) * priority;
					if (speed == 0) {
						speed = ctx.getRouter().getMinDefaultSpeed() * priority;
					}
					double distOnRoadToPass = positive ? posSegmentDist
							: negSegmentDist;
					double distStartObstacles = segment.distanceFromStart
							+ (positive ? obstaclePlusTime : obstacleMinusTime)
							+ distOnRoadToPass / speed;
					double distToFinalPoint = BinaryRoutePlanner
							.squareRootDist(x, y, targetEndX, targetEndY);
					// logRouteSegment("extend",
					// new RouteSegment(segment.getRoad(), segmentEnd));
					checkDataSourceObject(segment.getRoad(), segmentEnd,
							distStartObstacles, distToFinalPoint,
							dataSourceObjects, result);

					found = checkFoundRoute(segment.getRoad().id, segmentEnd,
							end);
					if (found) {
						end.parentRoute = segment;
						end.parentSegmentEnd = segmentEnd;
						end.distanceFromStart = distStartObstacles;
						end.distanceToEnd = 0;
						return result;
					}

					long l = (((long) x) << 31) + (long) y;
					RouteSegment next = tile.getSegment(l, ctx);

					if (next != null) {
						if ((next == segment || next.road.id == road.id)
								&& next.next == null) {
							continue;
						}

						found = processIntersections(ctx, router,
								graphSegments, visitedSegments,
								distStartObstacles, distToFinalPoint, segment,
								segmentEnd, next, end, dataSourceObjects,
								result);
						if (found) {
							return result;
						}
					}
				}
			}

			throw new Exception("route not found");
		}

		private void logRouteSegment(String prefix, RouteSegment segment) {
			log.debug(String.format(
					"prefix: %s, road name: %s, road id: %d, index: %d",
					prefix, (segment.getRoad().getName() == null ? "" : segment
							.getRoad().getName()), segment.getRoad().getId(),
					segment.getSegmentStart()));
		}

		private boolean checkDataSourceObject(RouteDataObject route,
				int segmentStart, double distanceFromStart,
				double distanceToEnd,
				TLongObjectHashMap<DataSourceObject> dataSourceObjects,
				PriorityQueue<DataSourceObject> toFill) {
			long nt = (route.id << BinaryRoutePlanner.ROUTE_POINTS)
					+ segmentStart;
			if (dataSourceObjects.contains(nt)) {
				DataSourceObject o = dataSourceObjects.get(nt);
				if (o != null && !toFill.contains(o)) {
					o.setDistanceFromStart(distanceFromStart);
					o.setDistanceToEnd(distanceToEnd);
					o.setMaxAllowedSpeed(route.getMaximumSpeed());
					o.attachSegment(new RouteSegment(route, segmentStart));
					toFill.add(o);
					return true;
				}
			}
			return false;
		}

		private void prepareDataSourceObject(
				ArrayList<DataSourceObject> dataSourceObjectList,
				TLongObjectHashMap<DataSourceObject> toFill,
				BinaryRoutePlanner router, RoutingContext ctx) {
			for (DataSourceObject o : dataSourceObjectList) {
				try {
					RouteSegment s = router.findRouteSegment(o.getLat(),
							o.getLon(), ctx);
					if (s != null) {

						RouteDataObject road = s.getRoad();
						long nt = (road.getId() << BinaryRoutePlanner.ROUTE_POINTS)
								+ s.getSegmentStart();
						toFill.put(nt, o);
					}
				} catch (IOException e) {
					log.debug("route segment not found");
				}
			}
		}

		private boolean checkFoundRoute(long id, int segmentEnd,
				RouteSegment end) {
			if (end == null) {
				return false;
			}
			if (end.getRoad().id == id && segmentEnd == end.segmentStart) {
				log.debug("route found");
				return true;
			}
			return false;
		}

		private boolean processIntersections(RoutingContext ctx,
				BinaryRoutePlanner router,
				PriorityQueue<RouteSegment> graphSegments,
				TLongObjectHashMap<RouteSegment> visitedSegments,
				double distFromStart, double distToFinalPoint,
				RouteSegment segment, int segmentEnd, RouteSegment inputNext,
				RouteSegment end,
				TLongObjectHashMap<DataSourceObject> dataSourceObjects,
				PriorityQueue<DataSourceObject> result) {
			boolean thereAreRestrictions = router.proccessRestrictions(ctx,
					segment.road, inputNext, false);
			Iterator<RouteSegment> nextIterator = null;
			if (thereAreRestrictions) {
				nextIterator = ctx.segmentsToVisitPrescripted.iterator();
			}
			RouteSegment next = inputNext;
			boolean hasNext = nextIterator == null || nextIterator.hasNext();
			while (hasNext) {
				if (nextIterator != null) {
					next = nextIterator.next();
				}
				long nts = (next.road.getId() << BinaryRoutePlanner.ROUTE_POINTS)
						+ next.segmentStart;
				boolean alreadyVisited = visitedSegments.contains(nts);
				if (!alreadyVisited) {
					double distanceToEnd = BinaryRoutePlanner.h(ctx,
							distToFinalPoint, next);
					if (next.parentRoute == null
							|| ctx.roadPriorityComparator(
									next.distanceFromStart, next.distanceToEnd,
									distFromStart, distanceToEnd) > 0) {
						next.distanceFromStart = distFromStart;
						next.distanceToEnd = distanceToEnd;
						if (next.parentRoute != null) {
							graphSegments.remove(next);
						}
						if (next.parentRoute == null) {
							checkDataSourceObject(next.getRoad(),
									next.getSegmentStart(),
									next.distanceFromStart, next.distanceToEnd,
									dataSourceObjects, result);
							boolean found = checkFoundRoute(next.getRoad()
									.getId(), next.getSegmentStart(), end);
							if (found) {
								return true;
							}
						}
						next.parentRoute = segment;
						next.parentSegmentEnd = segmentEnd;
						graphSegments.add(next);
						// logRouteSegment("intersection", next);
					}
				} else {
					// TODO: check
					if (distFromStart < next.distanceFromStart
							&& next.road.id != segment.road.id) {
						next.distanceFromStart = distFromStart;
						next.parentRoute = segment;
						next.parentSegmentEnd = segmentEnd;
					}
				}

				if (nextIterator == null) {
					next = next.next;
					hasNext = next != null;
				} else {
					hasNext = nextIterator.hasNext();
				}
			}
			return false;
		}

		public void stopCalculation() {

		}

	}

	public void downloadAndPlayDigest(PriorityQueue<DataSourceObject> target) {
		log.debug("download and play digest");
		ArrayList<DataSourceObject> ds = new ArrayList<DataSourceObject>();
		if (settings.ROADSPEAK_DIGEST_NUMBER.get() <= 4) {
			DataSourceObject o;
			for (int i = 0; i < settings.ROADSPEAK_DIGEST_NUMBER.get()
					&& !target.isEmpty(); i++) {
				o = target.poll();
				ds.add(o);
			}
		} else {
			DataSourceObject o;
			while (!target.isEmpty()) {
				o = target.poll();
				ds.add(o);
			}
		}
		downloadDigests(ds);
		playDigest(ds);
	}

	public void logRoute(RouteDataObject route) {
		String routeName = route.getName();
		if (routeName == null) {
			routeName = "";
		}
		String routeRef = route.getRef();
		if (routeRef == null) {
			routeRef = "";
		}
		log.debug("route full name: " + routeName + routeRef);
		for (int i = 0; i < route.pointsX.length; i++) {
			log.debug("point: " + MapUtils.get31LongitudeX(route.pointsX[i])
					+ "," + MapUtils.get31LatitudeY(route.pointsY[i]));
		}
		log.debug("route end");

	}

	private void playDigest(final ArrayList<DataSourceObject> toPlay) {
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				CommandPlayer p = map.getRoutingHelper().getVoiceRouter()
						.getPlayer();
				if (p instanceof TTSCommandPlayerImpl) {
					RoadSpeakPlugin.this.ttsPlayer = (TTSCommandPlayerImpl) p;
				}
				if (decision == null) {
					decision = new Decision();
				}
				decision.clear();
				decision.setTTSPlayer(ttsPlayer);
				ttsPlayer.speak(app.getString(R.string.start_play_digest));
				for (int i = 0; i < toPlay.size(); i++) {
					DataSourceObject o = toPlay.get(i);
					if (o instanceof MessageObject) {
						MessageObject message = (MessageObject) o;
						synchronized (message) {
							while (message.getFile() == null) {
								try {
									message.wait(5000);
								} catch (InterruptedException e) {
									e.printStackTrace();
								}
							}
							if (message.getFile().equals(Digest.UNAVAILABLE)) {
								continue;
							}
							if (ttsPlayer == null) {
								handler.post(new Runnable() {
									@Override
									public void run() {
										Toast.makeText(
												map,
												"texttospeech synthesis is not available",
												Toast.LENGTH_SHORT).show();
									}
								});
							} else {
								RouteSegment segment = message.getSegment();
								if (segment == null) {
									log.error("segment info missing");
								} else {
									String routeName = segment.getRoad()
											.getName();
									String routeRef = segment.getRoad()
											.getRef();
									if ((routeName == null || routeName
											.equals(""))
											&& (routeRef == null || routeRef
													.equals(""))) {
										log.debug("routename unavailable");
										ttsPlayer.speak(app
												.getString(R.string.routename_unavailable));
									} else if (routeName != null
											&& !routeName.equals("")) {
										log.debug("message from route name : "
												+ routeName);
										ttsPlayer.speak(prepare(routeName));
									} else if (routeRef != null
											&& !routeRef.equals("")) {
										log.debug("message from route name : "
												+ routeRef);
										ttsPlayer.speak(prepare(routeRef));
									}
								}
							}

							if (mPlayer == null) {
								mPlayer = new MediaPlayer();
							} else {
								mPlayer.reset();
							}
							try {
								mPlayer.setDataSource(message.getFile());
								mPlayer.prepare();
								mPlayer.start();
								while (mPlayer.isPlaying())
									;
								mPlayer.stop();
								mPlayer.release();
								mPlayer = null;
								Thread.sleep(1000);
								decision.ask(message);
							} catch (IllegalArgumentException e) {
								e.printStackTrace();
							} catch (IllegalStateException e) {
								e.printStackTrace();
							} catch (IOException e) {
								e.printStackTrace();
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
						synchronized (decision) {
							while (!decision.decided) {
								try {
									decision.wait();
								} catch (InterruptedException e) {
									e.printStackTrace();
								}
							}
							decision.decided = false;
						}
					}
				}
			}

		});
		thread.run();
	}

	protected String prepare(String routeName) {
		routeName = routeName.trim();
		try {
			int routeRef = Integer.parseInt(routeName);
			return Digest.ROUTE + " " + Integer.toString(routeRef);
		} catch (NumberFormatException e) {
		}

		int ind = routeName.lastIndexOf(" ") + 1;
		if (ind == 0) {
			return routeName;
		}
		String part = routeName.substring(ind);
		if (part.equalsIgnoreCase(Digest.ABBR_AVENUE)) {
			return routeName.substring(0, ind) + Digest.AVENUE;
		} else if (part.equalsIgnoreCase(Digest.ABBR_ROAD)) {
			return routeName.substring(0, ind) + Digest.ROAD;
		} else if (part.equalsIgnoreCase(Digest.ABBR_LANE)) {
			return routeName.substring(0, ind) + Digest.LANE;
		}
		return routeName;
	}

	private void downloadDigests(final ArrayList<DataSourceObject> toDownload) {
		Thread thread;
		String messageUrl = settings.ROADSPEAK_DOWNLOAD_MESSAGE_URL.get();
		for (int i = 0; i < toDownload.size(); i++) {
			DataSourceObject o = toDownload.get(i);
			if (o instanceof MessageObject) {
				final MessageObject message = (MessageObject) o;
				Date date = new Date(message.getTime());
				SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
				String dir = df.format(date);
				final String path = messageUrl + "/" + dir + "/"
						+ message.getMessageId();
				final String output = RoadSpeakPlugin.this.digestDirPath + "/"
						+ message.getMessageId();
				thread = new Thread(new Runnable() {
					@Override
					public void run() {
						synchronized (message) {
							boolean downloaded = downloadDigest(path, output);
							if (downloaded) {
								message.setFile(output);
							} else {
								message.setFile(Digest.UNAVAILABLE);
							}
							message.notifyAll();
						}
					}
				});
				thread.run();
			}
		}
	}

	public boolean downloadDigest(String path, String file) {
		try {
			URL url = new URL(path);
			URLConnection connection = url.openConnection();
			connection.connect();
			InputStream input = new BufferedInputStream(url.openStream());
			OutputStream output = new FileOutputStream(file);
			byte data[] = new byte[1024];
			int count;
			while ((count = input.read(data)) != -1) {
				output.write(data, 0, count);
			}
			output.flush();
			output.close();
			input.close();
			return true;
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return false;
	}

	public static class Digest {
		public static final int ONE_MESSAGE = 1;
		public static final int TWO_MESSAGE = 2;
		public static final int THREE_MESSAGE = 3;
		public static final int FOUR_MESSAGE = 4;
		public static final int ALL_MESSAGE = 5;
		public static final String UNAVAILABLE = "unavailable";

		public static final String ABBR_AVENUE = "ave";
		public static final String ABBR_ROAD = "rd";
		public static final String ABBR_LANE = "ln";

		public static final String AVENUE = "avenue";
		public static final String ROAD = "road";
		public static final String LANE = "lane";
		public static final String ROUTE = "route";

		public static final int ORDER_BY_SPEED = 1;
		public static final int ORDER_BY_DISTANCE_FROM_SRC = 2;
		public static final int ORDER_BY_DISTANCE_TO_DEST = 3;
		public static final int ORDER_BY_MIX = 4;

		public static final double THRESHOLD = 0.1D;
	}

	public class Decision {
		private ArrayList<RouteSegment> preferList = new ArrayList<RouteSegment>();
		private ArrayList<RouteSegment> avoidList = new ArrayList<RouteSegment>();
		private TTSCommandPlayerImpl ttsPlayer = null;
		public static final String QUESTION = "do you want to avoid this route? yes or no?";
		public final String[] ANS_YES = new String[] { "yes", "avoid" };
		public final String[] ANS_NO = new String[] { "no", "prefer" };
		private RouteSegment segment = null;
		public boolean decided = false;
		public final static double PENALTY = 3600; // an hour?

		public void clear() {
			preferList.clear();
			avoidList.clear();
		}

		public void setTTSPlayer(TTSCommandPlayerImpl ttsPlayer) {
			this.ttsPlayer = ttsPlayer;
		}

		public void ask(MessageObject message) {
			ttsPlayer.speak(QUESTION);
			segment = message.getSegment();
			handler.post(new Runnable() {
				@Override
				public void run() {
					getAnswer(preferList, avoidList);
				}
			});
		}

		protected void getAnswer(ArrayList<RouteSegment> preferList,
				ArrayList<RouteSegment> avoidList) {
			Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
			intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
					RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
			intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
					"net.osmand.plus.roadspeak");
			SpeechRecognizer recognizer = SpeechRecognizer
					.createSpeechRecognizer(map.getApplicationContext());
			RecognitionListener listener = new RecognitionListener() {

				@Override
				public void onBeginningOfSpeech() {
				}

				@Override
				public void onBufferReceived(byte[] buffer) {
				}

				@Override
				public void onEndOfSpeech() {
					Toast.makeText(map, "analysing voice...",
							Toast.LENGTH_SHORT).show();
				}

				@Override
				public void onError(int error) {
					log.error("speech to text error : " + error);
					ignore(segment);
					synchronized (decision) {
						decided = true;
						decision.notifyAll();
					}
				}

				@Override
				public void onEvent(int eventType, Bundle params) {
				}

				@Override
				public void onPartialResults(Bundle partialResults) {
				}

				@Override
				public void onReadyForSpeech(Bundle params) {
				}

				@Override
				public void onResults(Bundle results) {
					ArrayList<String> voiceResults = results
							.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
					boolean found = false;
					if (voiceResults == null) {
						log.debug("No voice result");
					} else {
						for (String ans : voiceResults) {
							if (parseAns(ans)) {
								found = true;
								break;
							}
						}
						if (!found) {
							ignore(segment);
						}
					}
					synchronized (decision) {
						decided = true;
						decision.notifyAll();
					}
				}

				@Override
				public void onRmsChanged(float rmsdB) {
				}

			};
			recognizer.setRecognitionListener(listener);
			recognizer.startListening(intent);
		}

		protected void ignore(RouteSegment segment) {
			String roadname = segment.getRoad().getName();
			Toast.makeText(map, "don't understand your answer",
					Toast.LENGTH_SHORT).show();
			log.debug("ignored : " + (roadname == null ? "" : roadname));
		}

		protected boolean parseAns(String ans) {
			for (String p : ANS_YES) {
				if (ans.equalsIgnoreCase(p)) {
					avoid(segment);
					return true;
				}
			}
			for (String p : ANS_NO) {
				if (ans.equalsIgnoreCase(p)) {
					prefer(segment);
					return true;
				}
			}
			return false;
		}

		private void prefer(RouteSegment segment) {
			preferList.add(segment);
			String roadname = segment.getRoad().getName();
			Toast.makeText(map, "don't avoid", Toast.LENGTH_SHORT).show();
			log.debug("prefered : " + (roadname == null ? "" : roadname));
		}

		private void avoid(RouteSegment segment) {
			avoidList.add(segment);
			String roadname = segment.getRoad().getName();
			Toast.makeText(map, "avoid", Toast.LENGTH_SHORT).show();
			log.debug("avoid : " + (roadname == null ? "" : roadname));
		}

		public ArrayList<RouteSegment> getPreferList() {
			return preferList;
		}

		public synchronized ArrayList<RouteSegment> getAvoidList() {
			return avoidList;
		}

	}

}
