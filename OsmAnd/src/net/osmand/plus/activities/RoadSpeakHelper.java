package net.osmand.plus.activities;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.ArrayList;

import net.osmand.LogUtil;
import net.osmand.osm.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.roadspeak.RoadSpeakPlugin;
import net.osmand.router.BinaryRoutePlanner.RouteSegment;

import org.apache.commons.logging.Log;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.location.Location;
import android.media.MediaRecorder;
import android.media.MediaRecorder.AudioSource;
import android.media.MediaRecorder.OutputFormat;
import android.os.AsyncTask;
import android.os.Environment;

public class RoadSpeakHelper {

	protected Context ctx;
	private OsmandSettings settings;
	private long lastTimeUpdated = 0L;
	private final static Log LOG = LogUtil.getLog(RoadSpeakHelper.class);

	private long onlineMemberCount = 0;
	private ArrayList<String> groupListName = new ArrayList<String>();

	private MediaRecorder recorder = null;
	private static String recordFilename;

	private RoadSpeakPlugin roadspeakPlugin;
	private ArrayList<DataSourceObject> dataSourceObjectList = new ArrayList<DataSourceObject>();
	public LatLon finalLocation;
	public Location currentLocation;

	@SuppressWarnings("unchecked")
	public synchronized ArrayList<DataSourceObject> cloneDataSourceObjectList() {
		return (ArrayList<DataSourceObject>) dataSourceObjectList.clone();
	}

	public void setRoadspeakPlugin(RoadSpeakPlugin roadspeakPlugin) {
		this.roadspeakPlugin = roadspeakPlugin;
	}

	public RoadSpeakHelper(Context ctx) {
		this.ctx = ctx;
		settings = ((OsmandApplication) ctx.getApplicationContext())
				.getSettings();
		recordFilename = Environment.getExternalStorageDirectory()
				.getAbsolutePath() + "/tmp_recording.3gpp";
	}

	public boolean isKeepLoggedInEnabled() {
		return settings.ROADSPEAK_KEEP_LOGGED_IN.get();
	}

	public synchronized long getOnlineMemberCount() {
		return onlineMemberCount;
	}

	public synchronized ArrayList<String> getGroupListName() {
		return groupListName;
	}

	public void fetchData(long time) {
		if (time - lastTimeUpdated > settings.ROADSPEAK_DIGEST_INTERVAL.get() * 1000) {
			new RoadSpeakFetcher().execute();
			lastTimeUpdated = time;
		}
	}

	private class RoadSpeakFetcher extends AsyncTask<Void, Void, Void> {

		@Override
		protected Void doInBackground(Void... arg0) {
			fetchData();
			return null;
		}

	}

	public void fetchData() {
		String url = MessageFormat.format(settings.ROADSPEAK_UPDATE_URL.get(),
				settings.ROADSPEAK_USER_NAME.get(),
				settings.ROADSPEAK_USER_PASSWORD.get());
		try {
			HttpParams params = new BasicHttpParams();
			HttpConnectionParams.setConnectionTimeout(params, 15000);
			DefaultHttpClient httpclient = new DefaultHttpClient(params);
			HttpRequestBase method = new HttpGet(url);
			LOG.info("RoadSpeak Update " + url);
			HttpResponse response = httpclient.execute(method);

			if (response.getStatusLine() == null
					|| response.getStatusLine().getStatusCode() != 200) {
				String msg;
				if (response.getStatusLine() != null) {
					msg = ctx.getString(R.string.failed_op);
				} else {
					msg = response.getStatusLine().getStatusCode() + " : "
							+ response.getStatusLine().getReasonPhrase();
				}
				LOG.error("Error fetching RoadSpeak update : " + msg);
			} else {
				InputStream is = response.getEntity().getContent();
				StringBuilder responseBody = new StringBuilder();
				if (is != null) {
					BufferedReader in = new BufferedReader(
							new InputStreamReader(is, "UTF-8"));
					String s;
					while ((s = in.readLine()) != null) {
						responseBody.append(s);
					}
					is.close();
				}
				httpclient.getConnectionManager().shutdown();
				LOG.info("RoadSpeak Update response : "
						+ responseBody.toString());

				updateData(responseBody.toString());
			}
		} catch (Exception e) {
			LOG.error("Failed connect to " + url, e);
		}

	}

	private synchronized void updateData(String responseBody) {
		try {
			JSONObject o = new JSONObject(responseBody);
			onlineMemberCount = o.getLong(ctx
					.getString(R.string.roadspeak_online_member_count_key));
			JSONArray array = o.getJSONArray(ctx
					.getString(R.string.roadspeak_grouplist_name_key));
			groupListName.clear();
			for (int i = 0; i < array.length(); i++) {
				groupListName.add(array.getString(i));
			}
		} catch (JSONException e) {
			LOG.error("Failed reading RoadSpeak response", e);
		}

	}

	public void sendMessage(double lat, double lon, double alt, double speed,
			double bearing, double hdop, long time) {
		if (recorder != null) {
			recorder.stop();
			recorder.release();
			recorder = null;
			uploadMessage(lat, lon, alt, speed, bearing, hdop, time);
		}
	}

	private static class RoadSpeakMessage {
		private final String username;
		private final String password;
		private final float lat;
		private final float lon;
		private final float alt;
		private final float speed;
		private final float bearing;
		private final float hdop;
		private final long time;
		private final File messageFile;

		public RoadSpeakMessage(String username, String password, float lat,
				float lon, float alt, float speed, float bearing, float hdop,
				long time, File messageFile) {
			this.username = username;
			this.password = password;
			this.lat = lat;
			this.lon = lon;
			this.alt = alt;
			this.speed = speed;
			this.bearing = bearing;
			this.hdop = hdop;
			this.time = time;
			this.messageFile = messageFile;
		}
	}

	private void uploadMessage(double lat, double lon, double alt,
			double speed, double bearing, double hdop, long time) {
		File messageFile = new File(recordFilename);
		RoadSpeakMessage message = new RoadSpeakMessage(
				settings.ROADSPEAK_USER_NAME.get(),
				settings.ROADSPEAK_USER_PASSWORD.get(), (float) lat,
				(float) lon, (float) alt, (float) speed, (float) bearing,
				(float) hdop, time, messageFile);
		new RoadSpeakUploader().execute(message);
	}

	private class RoadSpeakUploader extends
			AsyncTask<RoadSpeakMessage, Void, Void> {

		@Override
		protected Void doInBackground(RoadSpeakMessage... params) {
			for (RoadSpeakMessage message : params) {
				HttpClient httpclient = new DefaultHttpClient();
				HttpPost httppost = new HttpPost(
						settings.ROADSPEAK_UPLOAD_URL.get());
				MultipartEntity entity = new MultipartEntity();
				try {
					// TODO: sync with roadspeak_XXX_key
					entity.addPart(ctx.getString(R.string.username_key),
							new StringBody(message.username));
					entity.addPart(ctx.getString(R.string.password_key),
							new StringBody(message.password));
					entity.addPart(ctx.getString(R.string.lat_key),
							new StringBody(Float.toString(message.lat)));
					entity.addPart(ctx.getString(R.string.lon_key),
							new StringBody(Float.toString(message.lon)));
					entity.addPart(ctx.getString(R.string.alt_key),
							new StringBody(Float.toString(message.alt)));
					entity.addPart(ctx.getString(R.string.speed_key),
							new StringBody(Float.toString(message.speed)));
					entity.addPart(
							ctx.getString(R.string.roadspeak_bearing_key),
							new StringBody(Float.toString(message.bearing)));
					entity.addPart(ctx.getString(R.string.hdop_key),
							new StringBody(Float.toString(message.hdop)));
					entity.addPart(ctx.getString(R.string.time_key),
							new StringBody(Long.toString(message.time)));
					entity.addPart(ctx.getString(R.string.messagefile_key),
							new FileBody(message.messageFile));
					httppost.setEntity(entity);
					HttpResponse response = httpclient.execute(httppost);
					if (response.getStatusLine() == null
							|| response.getStatusLine().getStatusCode() != 200) {
						String msg;
						if (response.getStatusLine() != null) {
							msg = ctx.getString(R.string.failed_op);
						} else {
							msg = response.getStatusLine().getStatusCode()
									+ " : "
									+ response.getStatusLine()
											.getReasonPhrase();
						}
						LOG.error("Error uploading RoadSpeak message : " + msg);
					} else {
						InputStream is = response.getEntity().getContent();
						StringBuilder responseBody = new StringBuilder();
						if (is != null) {
							BufferedReader in = new BufferedReader(
									new InputStreamReader(is, "UTF-8"));
							String s;
							while ((s = in.readLine()) != null) {
								responseBody.append(s);
							}
							is.close();
						}
						httpclient.getConnectionManager().shutdown();
						LOG.info("RoadSpeak upload response : "
								+ responseBody.toString());
					}

				} catch (Exception e) {
					LOG.error("Failed connect to "
							+ settings.ROADSPEAK_UPLOAD_URL.get(), e);
				}
			}
			return null;
		}
	}

	public void recordMessage() {
		LOG.info("recording message");
		recorder = new MediaRecorder();
		recorder.setAudioSource(AudioSource.MIC);
		recorder.setOutputFormat(OutputFormat.THREE_GPP);
		recorder.setOutputFile(recordFilename);
		recorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
		try {
			recorder.prepare();
			recorder.start();
		} catch (IOException e) {
			LOG.error(LogUtil.TAG, e);
		}
	}

	public void enableRoadSpeakMessage(LatLon finalLocation,
			Location currentLocation) {
		this.finalLocation = finalLocation;
		this.currentLocation = currentLocation;
		if (roadspeakPlugin != null) {
			roadspeakPlugin.resetRoadSpeakFetchMessageTimer();
			roadspeakPlugin.startRoadSpeakFetchMessageTimer();
		}
	}

	public void disableRoadSpeakMessage() {
		if (roadspeakPlugin != null) {
			roadspeakPlugin.pauseRoadSpeakFetchMessageTimer();
		}
	}

	private class RoadSpeakEnvironmentUpdater extends
			AsyncTask<EnvironmentUpdateMessage, Void, Void> {

		@Override
		protected Void doInBackground(EnvironmentUpdateMessage... args) {
			for (EnvironmentUpdateMessage message : args) {
				updateEnvironment(message);
			}
			return null;
		}
	}

	private static class EnvironmentUpdateMessage {
		public Location loc;
		public LatLon finalLoc;
		public float accuracyForGpxAndRouting;

		public EnvironmentUpdateMessage(final Location loc,
				final LatLon finalLoc, final float accuracyForGpxAndRouting) {
			this.loc = loc;
			this.finalLoc = finalLoc;
			this.accuracyForGpxAndRouting = accuracyForGpxAndRouting;
		}
	}

	public void updateEnvironment(Location loc, LatLon finalLoc,
			float accuracyForGpxAndRouting) {
		EnvironmentUpdateMessage message = new EnvironmentUpdateMessage(loc,
				finalLoc, accuracyForGpxAndRouting);
		new RoadSpeakEnvironmentUpdater().execute(message);
	}

	private void updateEnvironment(EnvironmentUpdateMessage message) {
		Location loc = message.loc;
		LatLon finalLoc = message.finalLoc;
		float accuracyForGpxAndRouting = message.accuracyForGpxAndRouting;

		// TODO: why use float?
		if (loc != null) {
			if (!loc.hasAccuracy()
					|| loc.getAccuracy() < accuracyForGpxAndRouting) {
				String url = MessageFormat.format(
						settings.ROADSPEAK_UPDATE_ENVIRONMENT_URL.get(),
						settings.ROADSPEAK_USER_NAME.get(),
						settings.ROADSPEAK_USER_PASSWORD.get(),
						Float.toString((float) loc.getLatitude()),
						Float.toString((float) loc.getLongitude()),
						Float.toString((float) loc.getAltitude()),
						Float.toString((float) loc.getSpeed()),
						Float.toString((float) loc.getBearing()),
						Float.toString((float) loc.getAccuracy()),
						Long.toString(loc.getTime()),
						(finalLoc == null ? "" : Float
								.toString((float) finalLoc.getLatitude())),
						(finalLoc == null ? "" : Float
								.toString((float) finalLoc.getLongitude())));
				try {
					HttpParams params = new BasicHttpParams();
					HttpConnectionParams.setConnectionTimeout(params, 15000);
					DefaultHttpClient httpclient = new DefaultHttpClient(params);
					HttpRequestBase method = new HttpGet(url);
					LOG.info("RoadSpeak Update Environment " + url);
					HttpResponse response = httpclient.execute(method);
					if (response.getStatusLine() == null
							|| response.getStatusLine().getStatusCode() != 200) {
						String msg;
						if (response.getStatusLine() != null) {
							msg = ctx.getString(R.string.failed_op);
						} else {
							msg = response.getStatusLine().getStatusCode()
									+ " : "
									+ response.getStatusLine()
											.getReasonPhrase();
						}
						LOG.error("Error fetching RoadSpeak update environment response : "
								+ msg);
					} else {
						InputStream is = response.getEntity().getContent();
						StringBuilder responseBody = new StringBuilder();
						if (is != null) {
							BufferedReader in = new BufferedReader(
									new InputStreamReader(is, "UTF-8"));
							String s;
							while ((s = in.readLine()) != null) {
								responseBody.append(s);
							}
							is.close();
						}
						httpclient.getConnectionManager().shutdown();
						LOG.info("RoadSpeak Update Environment response : "
								+ responseBody.toString());

						updateEnvrionment(responseBody.toString());
					}
				} catch (Exception e) {
					LOG.error("Failed connect to " + url, e);
				}
			}
		}
	}

	private synchronized void updateEnvrionment(String responseBody) {
		try {
			JSONObject or = new JSONObject(responseBody);
			dataSourceObjectList.clear();
			JSONArray array = or.getJSONArray(ctx
					.getString(R.string.roadspeak_data_source_object_key));
			for (int i = 0; i < array.length(); i++) {
				JSONObject o = array.getJSONObject(i);
				int type = o.getInt(ctx.getString(R.string.roadspeak_type_key));
				float lat = (float) o.getDouble(ctx
						.getString(R.string.roadspeak_lat_key));
				float lon = (float) o.getDouble(ctx
						.getString(R.string.roadspeak_lon_key));
				float alt = (float) o.getDouble(ctx
						.getString(R.string.roadspeak_alt_key));
				float speed = (float) o.getDouble(ctx
						.getString(R.string.roadspeak_speed_key));
				float bearing = (float) o.getDouble(ctx
						.getString(R.string.roadspeak_bearing_key));
				float accuracy = (float) o.getDouble(ctx
						.getString(R.string.roadspeak_accuracy_key));
				long time = o.getLong(ctx
						.getString(R.string.roadspeak_time_key));
				int userId = o.getInt(ctx
						.getString(R.string.roadspeak_userid_key));
				String username = o.getString(ctx
						.getString(R.string.roadspeak_username_key));
				DataSourceObject obj;
				switch (type) {
				case FriendObject.TYPE: {
					obj = new FriendObject(lat, lon, alt, speed, bearing,
							accuracy, time, userId, username);
					dataSourceObjectList.add(obj);
					break;
				}
				case MessageObject.TYPE: {
					int messageId = o.getInt(ctx
							.getString(R.string.roadspeak_message_id_key));
					obj = new MessageObject(lat, lon, alt, speed, bearing,
							accuracy, time, messageId, userId, username);
					dataSourceObjectList.add(obj);
					break;
				}
				default: {
					LOG.error("Error reading environment message");
				}
				}

			}

		} catch (Exception e) {
			LOG.error("Failed reading RoadSpeak update environment response", e);
		}
	}

	public static class DataSourceObject {
		protected float lat;
		protected float lon;
		protected float alt;
		protected float speed;
		protected float bearing;
		protected float accuracy;
		protected long time;
		protected RouteSegment segment;

		public DataSourceObject(float lat, float lon, float alt, float speed,
				float bearing, float accuracy, long time) {
			this.lat = lat;
			this.lon = lon;
			this.alt = alt;
			this.speed = speed;
			this.bearing = bearing;
			this.accuracy = accuracy;
			this.time = time;
			this.segment = null;
		}

		public float getLat() {
			return lat;
		}

		public float getLon() {
			return lon;
		}

		public float getAlt() {
			return alt;
		}

		public float getSpeed() {
			return speed;
		}

		public float getBearing() {
			return bearing;
		}

		public float getAccuracy() {
			return accuracy;
		}

		public long getTime() {
			return time;
		}

		public RouteSegment getSegment() {
			return segment;
		}

		public void attachSegment(RouteSegment segment) {
			this.segment = segment;
		}
	}

	public static class FriendObject extends DataSourceObject {
		public final static int TYPE = 1;
		private int userId;
		private String username;

		public FriendObject(float lat, float lon, float alt, float speed,
				float bearing, float accuracy, long time, int userId,
				String username) {
			super(lat, lon, alt, speed, bearing, accuracy, time);
			this.userId = userId;
			this.username = username;
		}

		public int getUserId() {
			return userId;
		}

		public String getUsername() {
			return username;
		}
	}

	public static class MessageObject extends DataSourceObject {
		public final static int TYPE = 2;
		private int messageId;
		private int userId;
		private String username;
		private String file;

		public MessageObject(float lat, float lon, float alt, float speed,
				float bearing, float accuracy, long time, int messageId,
				int userId, String username) {
			super(lat, lon, alt, speed, bearing, accuracy, time);
			this.messageId = messageId;
			this.userId = userId;
			this.username = username;
			this.file = null;
		}

		public int getMessageId() {
			return messageId;
		}

		public int getUserId() {
			return userId;
		}

		public String getUsername() {
			return username;
		}

		public String getFile() {
			return file;
		}

		public void setFile(String file) {
			this.file = file;
		}
	}

	public synchronized void searchDataSourceObject(float top, float left,
			float bottom, float right, int zoom,
			ArrayList<DataSourceObject> toFill) {
		for (DataSourceObject o : dataSourceObjectList) {
			if (o.getLat() < top && o.getLat() > bottom && o.getLon() > left
					&& o.getLon() < right) {
				toFill.add(o);
			}
		}
	}

}
