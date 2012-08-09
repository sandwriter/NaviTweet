package net.osmand.plus.roadspeak;

import java.util.ArrayList;
import java.util.List;

import net.osmand.LogUtil;
import net.osmand.osm.LatLon;
import net.osmand.plus.R;
import net.osmand.plus.activities.RoadSpeakHelper;
import net.osmand.plus.activities.RoadSpeakHelper.DataSourceObject;
import net.osmand.plus.activities.RoadSpeakHelper.FriendObject;
import net.osmand.plus.activities.RoadSpeakHelper.MessageObject;
import net.osmand.plus.views.ContextMenuLayer;
import net.osmand.plus.views.OsmandMapLayer;
import net.osmand.plus.views.OsmandMapTileView;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.WindowManager;

public class RoadSpeakDataSourceLayer extends OsmandMapLayer implements ContextMenuLayer.IContextMenuProvider {
	private static final int startZoom = 10;
	public static final org.apache.commons.logging.Log log = LogUtil.getLog(RoadSpeakDataSourceLayer.class);
	
	private Paint paintIcon;
	private OsmandMapTileView view;
	private DisplayMetrics dm;
	private RoadSpeakHelper roadSpeakHelper;
	
	private ArrayList<DataSourceObject> objects = new ArrayList<DataSourceObject>();
	
	@Override
	public void initLayer(OsmandMapTileView view) {
		this.view = view;
		dm = new DisplayMetrics();
		paintIcon = new Paint();
		WindowManager wmgr = (WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE);
		wmgr.getDefaultDisplay().getMetrics(dm);
		roadSpeakHelper = view.getApplication().getRoadSpeakHelper();
	}

	@Override
	public void onDraw(Canvas canvas, RectF latLonBounds, RectF tilesRect,
			DrawSettings nightMode) {
		if(view.getZoom() >= startZoom){
			objects.clear();
			roadSpeakHelper.searchDataSourceObject(latLonBounds.top, latLonBounds.left, latLonBounds.bottom, latLonBounds.right, view.getZoom(), objects);
			for(DataSourceObject o : objects){
				int x = view.getRotatedMapXForPoint(o.getLat(), o.getLon());
				int y = view.getRotatedMapYForPoint(o.getLat(), o.getLon());
				Bitmap bmp = getIcon(view.getContext(), o);
				if(bmp != null){
					canvas.drawBitmap(bmp, x - bmp.getWidth() / 2, y - bmp.getHeight() /2, paintIcon);
				}				
			}
		}		
	}

	private Bitmap getIcon(Context ctx, DataSourceObject o) {
		Bitmap bmp = null;
		Options options = new BitmapFactory.Options();
		options.inScaled = false;
		options.inTargetDensity = dm.densityDpi;
		options.inDensity = dm.densityDpi;
		if(o instanceof FriendObject){			
			return BitmapFactory.decodeResource(ctx.getResources(), R.drawable.friend, options);			
		}else if(o instanceof MessageObject){			
			return BitmapFactory.decodeResource(ctx.getResources(), R.drawable.message, options);		
		}
		return null;
	}


	@Override
	public void destroyLayer() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean drawInScreenPixels() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void collectObjectsFromPoint(PointF point, List<Object> o) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public LatLon getObjectLocation(Object o) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getObjectDescription(Object o) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getObjectName(Object o) {
		// TODO Auto-generated method stub
		return null;
	}

}
