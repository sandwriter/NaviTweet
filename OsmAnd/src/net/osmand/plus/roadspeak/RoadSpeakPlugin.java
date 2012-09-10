package net.osmand.plus.roadspeak;

import gnu.trove.map.hash.TLongObjectHashMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.osmand.plus.activities.SettingsActivity;
import net.osmand.plus.render.NativeOsmandLibrary;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.views.MapInfoControl;
import net.osmand.plus.views.MapInfoLayer;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.TextInfoControl;
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
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Handler;
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
	private MapInfoControl roadspeakControl;
	private MapInfoControl roadspeakFetchControl;
	private Handler handler = new Handler();

	private final String ROADSPEAK_FETCH_MESSAGE_TIMER_ID = "roadspeak_fetch_message";
	private SimpleTimer roadspeakFetchMessageTimer = new SimpleTimer(
			ROADSPEAK_FETCH_MESSAGE_TIMER_ID);
	private final String ROADSPEAK_UPDATE_LOCATION_TIMER_ID = "roadspeak_update_location";
	private SimpleTimer updateLocationTimer = null;

	private MapActivity map;
	private RoutingHelper routingHelper;
	private MessageRouteHelper messageRouteHelper;

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
				R.drawable.roadspeak_logged_in_big,
				R.string.map_widget_roadspeak, "roadspeak", false,
				EnumSet.of(ApplicationMode.CAR, ApplicationMode.PEDESTRIAN),
				EnumSet.noneOf(ApplicationMode.class), 25);
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

	private void fetchAndPlayMessage() {
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
			synchronized(app){
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
		roadspeakFetchMessageTimer.start();
	}

	public void pauseRoadSpeakFetchMessageTimer() {
		roadspeakFetchMessageTimer.pause();
	}

	public void onCountDownStart(String id) {
		fetchAndPlayMessage();
	}

	public void onCountDownFinished(String id) {
		fetchAndPlayMessage();
		pauseRoadSpeakFetchMessageTimer();
		resetRoadSpeakFetchMessageTimer();
		startRoadSpeakFetchMessageTimer();
	}

	public void onCountDown(String id) {
		roadspeakFetchControl.updateInfo();
	}

	@Override
	public void updateLayers(OsmandMapTileView mapView, MapActivity activity) {
		if (roadspeakControl == null || roadspeakFetchControl == null) {
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
					long count = app.getRoadSpeakHelper()
							.getOnlineMemberCount();
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
		cat.addPreference(activity.createTimeListPreference(
				settings.ROADSPEAK_DIGEST_INTERVAL, new int[] {}, new int[] {
						5, 10, 15, 30, 60, 120 }, 1,
				R.string.roadspeak_digest_interval,
				R.string.roadspeak_digest_interval_description));
		cat.addPreference(activity.createTimeListPreference(
				settings.ROADSPEAK_UPDATE_INTERVAL, new int[] { 5, 10, 30 },
				new int[] { 1, 5, 10 }, 1, R.string.roadspeak_update_interval,
				R.string.roadspeak_update_interval_description));
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

				@Override
				public void onStart() {

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
			new Thread(new Runnable() {
				@Override
				public void run() {
					onStart();
				}
			}).start();
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

		public void onStart() {
			RoadSpeakPlugin.this.onCountDownStart(SimpleTimer.this.id);
		}

		public void pause() {
			schedulerHandler.cancel(true);
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
							Toast.makeText(map,
									"number of objects: " + target.size(),
									Toast.LENGTH_SHORT).show();
						}

					});
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
			Comparator<DataSourceObject> dataComparator = new Comparator<DataSourceObject>() {
				@Override
				public int compare(DataSourceObject o1, DataSourceObject o2) {
					return Float.compare(o1.getSpeed(), o2.getSpeed());
				}
			};
			PriorityQueue<DataSourceObject> result = new PriorityQueue<DataSourceObject>(
					20, dataComparator);
			ApplicationMode mode = routingHelper.getAppMode();
			if (mode != ApplicationMode.CAR) {
				return result;
			}
			log.debug("start routeQuery");
			BinaryMapIndexReader[] files = app.getResourceManager()
					.getRoutingMapFiles();
			BinaryRoutePlanner router = new BinaryRoutePlanner(
					NativeOsmandLibrary.getLoadedLibrary(), files);
			File routingXml = app.getSettings().extendOsmandPath(
					ResourceManager.ROUTING_XML);
			log.debug("after init res");
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
			log.debug("found start segment");
			checkDataSourceObject(start.getRoad().id, start.getSegmentStart(),
					dataSourceObjects, result);

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
			log.debug("start routing");

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

			log.debug("start A*");
			graphSegments.add(start);
			while (!graphSegments.isEmpty()) {
				RouteSegment segment = graphSegments.poll();
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
						log.debug("check intersection");

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

		private void checkDataSourceObject(long id, int segmentStart,
				TLongObjectHashMap<DataSourceObject> dataSourceObjects,
				PriorityQueue<DataSourceObject> toFill) {
			long nt = (id << BinaryRoutePlanner.ROUTE_POINTS) + segmentStart;
			if (dataSourceObjects.contains(nt)) {
				DataSourceObject o = dataSourceObjects.get(nt);
				if (o != null) {
					toFill.add(o);
				}
			}

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
							checkDataSourceObject(next.getRoad().getId(),
									next.getSegmentStart(), dataSourceObjects,
									result);
							boolean found = checkFoundRoute(next.getRoad()
									.getId(), next.getSegmentStart(), end);
							if (found) {
								return true;
							}
						}
						next.parentRoute = segment;
						next.parentSegmentEnd = segmentEnd;
						graphSegments.add(next);
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

}
