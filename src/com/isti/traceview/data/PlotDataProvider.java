package com.isti.traceview.data;

import java.awt.Color;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Observable;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import com.isti.traceview.TraceViewException;
import com.isti.traceview.common.IEvent;
import com.isti.traceview.common.Station;
import com.isti.traceview.common.TimeInterval;
import com.isti.traceview.gui.ColorModeBySource;
import com.isti.traceview.gui.IColorModeState;
import com.isti.traceview.processing.FilterFacade;
import com.isti.traceview.processing.IFilter;
import com.isti.traceview.processing.Rotation;

/**
 * <p>
 * Class for trace representation, produces and holds pixelized trace view and
 * manage pixalization policy.
 * </p>
 * <p>
 * The concept of a pixelized view is a realization of a simple idea that the
 * computer screen can fit up to a couple of thousand horizontal data points
 * (pixels) at most, and we cannot show all trace points on the computer's
 * screen. So we have to pixelize raw data, i.e each pixelized visible screen
 * point contain information about some time range of raw data. In this way we
 * can significantly speed-up plotting capability of the program.
 * </p>
 * <p>
 * Observed by associated Slot, i.e can report of changes to observing
 * instances.
 * </p>
 * 
 * @author Max Kokoulin
 */

public class PlotDataProvider extends RawDataProvider implements IChannel {
	public static final long				serialVersionUID			= 1;
	private static Logger					lg							= Logger.getLogger(PlotDataProvider.class);

	/**
	 * Point count which we have in RAM for whole time range
	 */
	private static final int				initPointCount				= 10000;

	/**
	 * Set of events attached to this data provider
	 * 
	 * @uml.property name="Events"
	 * @uml.associationEnd multiplicity="(0 -1)"
	 *                     inverse="plotDataProvider:com.isti.xmax.common.IEvent"
	 * @uml.association name="has events"
	 */
	protected transient SortedSet<IEvent>	events;

	/**
	 * Time of last access to data
	 */
	private transient Date					lastAccessed				= null;

	/**
	 * Time range of last query of data
	 */
	private transient TimeInterval			viewingInterval				= null;

	/**
	 * List of precalculated {@link PlotDataPoint}s on the full time range of
	 * channel to use on wide zooms
	 */
	protected List<PlotDataPoint[]>			pointsCache					= null;

	/**
	 * May be used by ColorModeByTrace to color trace in manual mode.
	 */
	private Color							manualColor					= Color.BLACK;

	protected boolean						usePixelCache				= true;

	private PixelizeArguments				lastUsedPixelizeArguments	= null;

	public PlotDataProvider(String channelName, Station station, String networkName, String locationName) {
		super(channelName, station, networkName, locationName);
		events = Collections.synchronizedSortedSet(new TreeSet<IEvent>());
	}

	public PlotDataProvider() {
		super();
		events = Collections.synchronizedSortedSet(new TreeSet<IEvent>());
	}

	/**
	 * Initialize point cache, fill it with initPointCount points, this cache is
	 * used to show big parts of data, and raw data access during zooming
	 * happens only to limited small parts of data
	 */
	public void initPointCache(IColorModeState colorMode) {
		if (usePixelCache) {
			pointsCache = pixelize(new PixelizeArguments(getTimeRange(), initPointCount, null, colorMode, true));
		}
	}

	/**
	 * From interface Observer
	 */
	public void update(Observable o, Object arg) {
		lg.debug(this + ": update request from " + o);
		TimeInterval ti = (TimeInterval) arg;
		lg.debug("PlotDataProvider " + this + " updating for range " + ti + " due to request from " + o.getClass().getName());
		if ((viewingInterval == null) || viewingInterval.isIntersect(ti)) {
			notifyObservers(ti);
		}
	}

	/**
	 * Generate rotated plot data
	 * 
	 * @param ti
	 *            Requested time interval
	 * @param pointCount
	 *            - requested count of points
	 * @param rotation
	 *            - rotation data, if null no rotation
	 * @param filter
	 *            - filter to apply
	 * @return generated plot data to draw
	 * @throws TraceViewException
	 */
	public PlotData getPlotData(TimeInterval ti, int pointCount, Rotation rotation, IFilter filter, IColorModeState colorMode) throws TraceViewException {
		if (rotation == null) {
			return getPlotData(ti, pointCount, filter, colorMode);
		} else {
			return rotation.rotate(this, ti, pointCount, filter, colorMode);
		}
	}

	/**
	 * Generate plot data
	 * 
	 * @param ti
	 *            Requested time interval
	 * @param pointCount
	 *            - requested count of points
	 * @param filter
	 *            - filter to apply
	 * @return generated plot data to draw
	 * @throws TraceViewException
	 */
	private PlotData getPlotData(TimeInterval ti, int pointCount, IFilter filter, IColorModeState colorMode) {
		lg.debug("getPlotData: " + this + "; " + ti + "(" + ti.getStart() + "-" + ti.getEnd() + ")" + "; pointCount " + pointCount);

		// This list used when we cannot use pointsCache due to too small zoom,
		// calculated every time afresh.
		List<PlotDataPoint[]> points = null;

		if (pointsCache == null) {
			initPointCache(colorMode);
		}
		// Time range need to be pixelized - intersection of requested
		// pixalization range and channel's time range
		PlotData ret = new PlotData(getPlotDataLabel(), getUnitName(), getColor(), getScale());

		TimeInterval effectiveTimeRange = TimeInterval.getIntersect(ti, isNetworkDataProvider() ? ti : getTimeRange());
		if (effectiveTimeRange != null) {
			if (pointsCache == null || ((pointCount > pointsCache.size() * new Double(effectiveTimeRange.getDuration()) / new Double(getTimeRange().getDuration())) || filter != null)) {
				PixelizeArguments args = new PixelizeArguments(effectiveTimeRange, new Double(2 * pointCount * effectiveTimeRange.getDuration() / new Double(ti.getDuration()).intValue()).intValue(),
						filter, colorMode, false);
				lg.debug("Args equals: " + args.equals(getLastUsedPixelizeArguments()) + "; last arg points null: "
						+ (getLastUsedPixelizeArguments() == null ? "yes" : getLastUsedPixelizeArguments().points == null));
				if (args.equals(getLastUsedPixelizeArguments()) && (getLastUsedPixelizeArguments().points != null)) {
					if (getLastUsedPixelizeArguments().points.size() == 0) {
						lg.debug("getPlotData: " + this + ": no data");
						points = null;
					} else {
						lg.debug("getPlotData: " + this + ": using precalculated points");
						points = getLastUsedPixelizeArguments().points;
					}
				} else if (!isLoadingStarted()) {
					setLastUsedPixelizeArguments(args);
					args.points = pixelize(args);
					if (args.points != null && args.points.size() > 0) {
						setChanged();
						ret.ignoreFlag = true;
						notifyObservers(args.ti);
					}
				}
			} else {
				points = new ArrayList<PlotDataPoint[]>();
				int startIndex = new Double((effectiveTimeRange.getStart() - getTimeRange().getStart()) * initPointCount / getTimeRange().getDuration()).intValue();
				if (startIndex < 0) {
					for (int i = -startIndex; i < 0; i++) {
						 lg.debug("getPlotData: add empty points in the beginning");
						PlotDataPoint[] intervalPoints = new PlotDataPoint[1];
						intervalPoints[0] = new PlotDataPoint(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, -1, -1, -1, null);
						points.add(intervalPoints);
					}
					startIndex = 0;
				}
				int endIndex = new Double((effectiveTimeRange.getEnd() - getTimeRange().getStart()) * initPointCount / getTimeRange().getDuration()).intValue();
				if (endIndex > initPointCount) {
					points.addAll(pointsCache.subList(startIndex, initPointCount));
					for (int i = initPointCount; i < endIndex; i++) {
						 lg.debug("getPlotData: add empty points in the end");
						PlotDataPoint[] intervalPoints = new PlotDataPoint[1];
						intervalPoints[0] = new PlotDataPoint(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, -1, -1, -1, null);
						points.add(intervalPoints);
					}
				} else {
					points.addAll(pointsCache.subList(startIndex, endIndex));
				}
				 lg.debug("Use data points from cache to calculate data, indexes: " + startIndex + "-" + endIndex);
			}
			if ((points != null)) {
				double timeRatio = (ti.getDuration()) / new Double(pointCount);
				for (int i = 0; i < pointCount; i++) {
					// we divide requested time range into pointCount time
					// slices and calculate data to
					// display for every slice
					double startSlice = ti.getStart() + i * timeRatio; // start
																		// slice
																		// time
					double endSlice = ti.getStart() + ((i + 1.0) * timeRatio); // end
																				// slice
																				// time
					if (!((startSlice >= effectiveTimeRange.getEnd() && endSlice >= effectiveTimeRange.getEnd()) || (startSlice <= effectiveTimeRange.getStart() && endSlice <= effectiveTimeRange
							.getStart()))) {
						// if effective time range intersects this time slice
						int startIndex = new Long(Math.round(new Double((startSlice - effectiveTimeRange.getStart()) * points.size()) / new Double(effectiveTimeRange.getDuration()))).intValue();
						if (startIndex < 0) {
							startIndex = 0;
						}
						int endIndex = new Long(Math.round(new Double((endSlice - effectiveTimeRange.getStart()) * points.size()) / new Double(effectiveTimeRange.getDuration()))).intValue();
						if (endIndex > points.size()) {
							endIndex = points.size();
						}
						if ((startIndex == endIndex) && (endIndex != points.size())) {
							// to avoid gaps on very large zoom
							endIndex = endIndex + 1;
						}
						List<PlotDataPoint[]> data = points.subList(startIndex, endIndex);
						List<SliceData> sliceDataList = new ArrayList<SliceData>();
						int j = 0;
						for (PlotDataPoint[] sublist : data) {
							int k = 0;
							for (PlotDataPoint value : sublist) {
								// lg.debug("Index " + (startIndex + j) +
								// ", set " + k + ", value " + value);
								if (sliceDataList.size() <= k) {
									sliceDataList.add(new SliceData());
								}
								if (value.getTop() > sliceDataList.get(k).top) {
									sliceDataList.get(k).top = value.getTop();
								}
								if (value.getBottom() < sliceDataList.get(k).bottom) {
									sliceDataList.get(k).bottom = value.getBottom();
								}
								if (value.getMean() != Double.POSITIVE_INFINITY) {
									sliceDataList.get(k).sum = sliceDataList.get(k).sum + value.getMean();
									sliceDataList.get(k).segmentNumber = value.getSegmentNumber();
									sliceDataList.get(k).continueAreaNumber = value.getContinueAreaNumber();
									sliceDataList.get(k).rdpNumber = value.getRawDataProviderNumber();
									sliceDataList.get(k).dataPointCount++;
								}
								k++;
							}
							j++;
						}
						if (events == null) {
							events = Collections.synchronizedSortedSet(new TreeSet<IEvent>()); // class
																								// was
																								// deserialized
						}
						SortedSet<EventWrapper> evts = new TreeSet<EventWrapper>();
						for (IEvent event : events) {
							long eventTime = event.getStartTime().getTime();
							if (eventTime > startSlice && eventTime <= endSlice) {
								evts.add(new EventWrapper(event, true));
							} else if (!((eventTime >= endSlice && eventTime + event.getDuration() >= endSlice || (eventTime <= startSlice && eventTime + event.getDuration() <= startSlice)))) {
								evts.add(new EventWrapper(event, false));
							}
						}
						/*
						 * if (new Double(endSlice).longValue() - new
						 * Double(startSlice).longValue() > 0) { evts =
						 * events.subSet(new DefaultEvent(new Date(new
						 * Double(startSlice).longValue())), new
						 * DefaultEvent(new Date(new Double(
						 * endSlice).longValue()))); }
						 */

						// lg.debug("getPlotData: PlotDataProvider " + this +
						// ": Adding plot data points set " + i + "; time " +
						// TimeInterval.formatDate(new Date(new
						// Double(startSlice).longValue()),TimeInterval.DateFormatType.DATE_FORMAT_NORMAL)
						// + " - " +
						// TimeInterval.formatDate(new Date(new
						// Double(endSlice).longValue()),TimeInterval.DateFormatType.DATE_FORMAT_NORMAL)
						// + "(" + startSlice + "-" + endSlice+ ")" +
						// ", for indexes " + startIndex + " - " + endIndex);
						PlotDataPoint[] pdpArray = new PlotDataPoint[sliceDataList.size()];
						int m = 0;
						for (SliceData sliceData : sliceDataList) {
							pdpArray[m] = sliceData.getPoint(evts);
							// lg.debug("Added point " + m + ": " +
							// pdpArray[m].toString());
							m++;
						}
						ret.addPixel(pdpArray);
						if (evts.size() > 0) {
							lg.debug("Event time: " + TimeInterval.formatDate(evts.first().getEvent().getStartTime(), TimeInterval.DateFormatType.DATE_FORMAT_NORMAL) + "("
									+ evts.first().getEvent().getStartTime().getTime() + ")" + "; point number " + ret.getPointCount());
						}
					} else {
						// lg.debug("if effective time range doesn't contain this time slice - added empty point");
						PlotDataPoint[] pdpArray = new PlotDataPoint[1];
						pdpArray[0] = new PlotDataPoint(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, -1, -1, -1, null);
						ret.addPixel(pdpArray);
					}
				}
				lastAccessed = new Date();
			}
		}
		lg.debug("getPlotData end: " + this + " returned " + ret);
		return ret;
	}

	protected String getPlotDataLabel() {
		return getName();
	}

	class PixelizeArguments {
		TimeInterval			ti			= null;
		int						pointCount	= 0;
		IFilter					filter;
		IColorModeState			colorMode;
		boolean					silent		= false;
		List<PlotDataPoint[]>	points		= null;

		public PixelizeArguments(TimeInterval ti, int pointCount, IFilter filter, IColorModeState colorMode, boolean silent) {
			this.ti = ti;
			this.pointCount = pointCount;
			this.filter = filter;
			this.colorMode = colorMode;
			this.silent = silent;
		}

		public String toString() {
			return ti + "; " + pointCount;
		}

		public boolean equals(Object obj) {
			if (!(obj instanceof PixelizeArguments))
				return false;
			else {
				PixelizeArguments pa = (PixelizeArguments) obj;
				return ti.equals(pa.ti) && pointCount == pa.pointCount && silent == pa.silent && ((filter == null && pa.filter == null) || (filter == null ? false : filter.equals(pa.filter)))
						&& ((colorMode == null && pa.colorMode == null) || (colorMode == null ? false : colorMode.equals(pa.colorMode)));
			}
		}
	}

	/**
	 * Pixelize raw data
	 * 
	 * @param ti
	 *            Time interval to pixelize
	 * @param pointCount
	 *            Requested count of points
	 * @param filter
	 *            filter to apply to raw data before pixelization
	 * @return List of PlotDataPoint
	 */
	private List<PlotDataPoint[]> pixelize(PixelizeArguments args) {
		lg.debug("PlotDataProvider.pixelize " + this + "; " + args);
		List<PlotDataPoint[]> pointSet = null;
		List<Segment> segments = getRawData(args.ti);
		if (segments != null) {
			List<SegmentData> rd = new ArrayList<SegmentData>();
			pointSet = Collections.synchronizedList(new ArrayList<PlotDataPoint[]>());
			// combine segments if no gap and colormode is not by source, to
			// correct filtering
			for (int i = 0; i < segments.size(); i++) {
				// ALL requested for pixelization time range in this segment
				Segment segment = segments.get(i);
				TimeInterval currentSegmentDataTI = TimeInterval.getIntersect(args.ti, new TimeInterval(segment.getStartTime(), segment.getEndTime()));
				SegmentData segmentData = segment.getData(currentSegmentDataTI);
				if (segmentData != null) {
					if (i == 0 || args.colorMode instanceof ColorModeBySource || Segment.isDataBreak(segments.get(i - 1).getEndTime().getTime(), segmentData.startTime, segmentData.sampleRate)) {
						rd.add(segmentData);
					} else {
						SegmentData last = rd.get(rd.size() - 1);
						int lastLength = last.data.length;
						last.data = Arrays.copyOf(last.data, lastLength + segmentData.data.length);
						for (int j = 0; j < segmentData.data.length; j++) {
							last.data[lastLength + j] = segmentData.data[j];
						}
					}
				}
			}
			// filtering
			if (args.filter != null) {
				FilterFacade ff = new FilterFacade(args.filter, this);
				List<SegmentData> filteredRawData = new ArrayList<SegmentData>();
				for (SegmentData segmentData : rd) {
					filteredRawData.add(new SegmentData(segmentData.startTime, segmentData.sampleRate, segmentData.sourceSerialNumber, segmentData.channelSerialNumber, segmentData.continueAreaNumber,
							segmentData.previous, segmentData.next, ff.filter(segmentData.data)));
				}
				rd = filteredRawData;
			}
			double interval = (args.ti.getDuration()) / new Double(args.pointCount);
			double time = args.ti.getStart();
			for (int i = 0; i < args.pointCount; i++) {
				// lg.debug("Iteration # "+ i + ", processing interval " + time
				// + " - " + (time+interval));
				// segments which has data in the interval
				SegmentData[] intervalData = getSegmentData(rd, time, time + interval);
				if (intervalData != null) {
					int k = 0;
					PlotDataPoint[] intervalPoints = new PlotDataPoint[intervalData.length];
					for (SegmentData segData : intervalData) {
						TimeInterval currentSegmentDataTI = new TimeInterval(segData.startTime, segData.endTime());
						// lg.debug("Processing segment " + segment +
						// "on interval " + currentSegmentDataTI);
						double top = Double.NEGATIVE_INFINITY;
						double bottom = Double.POSITIVE_INFINITY;
						double sum = 0.0;
						int rawDataPointCount = 0;
						SegmentData data;
						if (i == (args.pointCount - 1)) {
							data = segData.getData(time, args.ti.getEnd());
						} else {
							data = segData.getData(time, time + interval);
						}
						rawDataPointCount = data.data.length;
						if (rawDataPointCount > 0) {
							// lg.debug("Data present, meaning interval");
							for (int value : data.data) {
								if (value > top) {
									top = value;
								}
								if (value < bottom) {
									bottom = value;
								}
								sum = sum + value;
							}
							intervalPoints[k] = new PlotDataPoint(top, bottom, sum / rawDataPointCount, segData.channelSerialNumber, segData.sourceSerialNumber, segData.continueAreaNumber, null);
							// lg.debug("Data present, point " + k + " added: "
							// + intervalPoints[k]);
						} else {

							if (currentSegmentDataTI.isContain(new Double(time).longValue())) {
								rawDataPointCount = 1;

								double value = segData.interpolateValue(time);
								// lg.debug("Interpolated value, point " + k +
								// " added: " + value);
								intervalPoints[k] = new PlotDataPoint(value, value, value, segData.channelSerialNumber, segData.sourceSerialNumber, segData.continueAreaNumber, null);

							} else {
								// lg.debug("Interpolated value, point " + k +
								// " absent");
								intervalPoints[k] = new PlotDataPoint(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, -1, -1, -1, null);
							}
						}
						k++;
					}
					pointSet.add(intervalPoints);
				} else {
					// lg.debug("Pixelizing : segment null");
					PlotDataPoint[] intervalPoints = new PlotDataPoint[1];
					intervalPoints[0] = new PlotDataPoint(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, -1, -1, -1, null);
					pointSet.add(intervalPoints);
				}
				time = time + interval;
			}
		}
		lg.debug("PlotDataProvider.pixelize end " + this);
		return pointSet;
	}
	
	public void deleteRawData(TimeInterval ti) {
		super.deleteRawData(ti);
		setLastUsedPixelizeArguments(null);
		pointsCache = null;
	}

	/**
	 * @param sps
	 *            Segments list
	 * @param start
	 *            start time
	 * @param end
	 *            end time
	 * @return subset of segment list which lies in the requested time interval.
	 *         For one channel, the normal situation is none or one segment, but
	 *         it can be bigger count in the case of segment overlapping. If no
	 *         segments found, return null.
	 */
	private static SegmentData[] getSegmentData(List<SegmentData> sps, double start, double end) {
		List<SegmentData> ret = new ArrayList<SegmentData>();
		Iterator<SegmentData> it = sps.iterator();
		while (it.hasNext()) {
			SegmentData segData = it.next();
			if (segData != null) {
				long retStart = segData.startTime;
				long retEnd = segData.endTime();
				if (!((start >= retEnd && end >= retEnd) || (start <= retStart && end <= retStart))) {
					ret.add(segData);
				}
			}
		}
		if (ret.size() == 0) {
			return null;
		} else {
			return ret.toArray(new SegmentData[1]);
		}
	}

	/**
	 * @param sps
	 *            Segments list
	 * @param start
	 *            start time
	 * @param end
	 *            end time
	 * @return subset of segment list which lies in the requested time interval.
	 *         For one channel, the normal situation is none or one segment, but
	 *         it can be bigger count in the case of segment overlapping. If no
	 *         segments found, return null.
	 */
	private static Segment[] getSegment(List<Segment> sps, double start, double end) {
		List<Segment> ret = new ArrayList<Segment>();
		Iterator<Segment> it = sps.iterator();
		while (it.hasNext()) {
			Segment seg = it.next();
			long retStart = seg.getStartTime().getTime();
			long retEnd = seg.getEndTime().getTime();
			if (!((start >= retEnd && end >= retEnd) || (start <= retStart && end <= retStart))) {
				ret.add(seg);
			}
		}
		if (ret.size() == 0) {
			return null;
		} else {
			return ret.toArray(new Segment[1]);
		}
	}

	/**
	 * Getter of the property <tt>Events</tt>
	 * 
	 * @return set of all events
	 * @uml.property name="Events"
	 */
	public SortedSet<IEvent> getEvents() {
		return events;
	}

	/**
	 * Get set of events which have given start time
	 * 
	 * @param time
	 * @return set of events
	 */
	public SortedSet<IEvent> getEvents(Date time) {
		return getEvents(time, 0);
	}

	/**
	 * set of events which lies near given time
	 * 
	 * @param time
	 * @param precision
	 *            time range to find, in milliseconds
	 * @return set of events
	 */
	public SortedSet<IEvent> getEvents(Date time, long precision) {
		SortedSet<IEvent> ret = Collections.synchronizedSortedSet(new TreeSet<IEvent>());
		for (IEvent event : events) {
			if (event.getStartTime().getTime() > time.getTime() && event.getStartTime().getTime() < time.getTime() + 2 * precision) {
				ret.add(event);
			}
		}
		return ret;
	}

	/**
	 * Adds event to plot data provider
	 * 
	 * @param event
	 *            Event to add
	 * @return <tt>true</tt> if events set did not already contain the given one
	 */
	public boolean addEvent(IEvent event) {
		if (events == null) {
			events = Collections.synchronizedSortedSet(new TreeSet<IEvent>());
		}
		return events.add(event);
	}

	/**
	 * Remove event
	 * 
	 * @param event
	 *            to remove
	 * @return <tt>true</tt> if events set contained the specified event
	 */
	public boolean removeEvent(IEvent event) {
		if (events == null) {
			events = Collections.synchronizedSortedSet(new TreeSet<IEvent>());
		}
		return events.remove(event);
	}

	/**
	 * Adds event set to plot data provider
	 * 
	 * @param evt
	 */
	public void addEvents(Set<IEvent> evt) {
		lg.debug("Adding " + evt.size() + " events to plot data provider" + this);
		if (events == null) {
			events = Collections.synchronizedSortedSet(new TreeSet<IEvent>());
		}
		events.addAll(evt);
	}

	public Date getLastAccessed() {
		return lastAccessed;
	}

	/**
	 * Get rotated raw data
	 * 
	 * @param rotation
	 *            to process data
	 * @return rotated raw data
	 */
	public List<Segment> getRawData(Rotation rotation) {
		if (rotation == null) {
			return super.getRawData();
		} else {
			try {
				return rotation.rotate(this, getTimeRange());
			} catch (TraceViewException e) {
				e.printStackTrace();
				return null;
			}
		}
	}

	/**
	 * Returns a string representation of the PlotDataProvider for debug
	 * purposes.
	 * 
	 * @return a string representation of the PlotDataProvider.
	 */
	public String toString() {
		return "PlotDataProvider: " + getName();
	}

	/**
	 * Get plot data provider name, currently we use name of underlies raw data
	 * provider
	 * 
	 * @return a string representation of the PlotDataProvider.
	 */
	public String getName() {
		return super.getName();
	}

	/**
	 * Dumps trace to file in temporary storage in internal format
	 */
	public void dump(String serialFileName) {
		ObjectOutputStream out = null;
		try {
			out = new ObjectOutputStream(new FileOutputStream(serialFileName + ".SER"));
			setDataStream(serialFileName + ".DATA");
			synchronized (this) {
				lg.info("Serializing " + this + " to file " + serialFileName);
				out.writeObject(this);
				notifyAll();
			}
		} catch (Exception ex) {
			lg.error("Can't save channel: " + ex);
		} finally {
			try {
				setDataStream(null);
				out.close();
			} catch (IOException e) {
				// Do nothing
			}
		}
	}

	/**
	 * Loads trace from serialized file in temporary storage
	 */
	public static PlotDataProvider load(String fileName) {
		lg.debug("Deserializing channel: from file " + fileName);
		PlotDataProvider channel = null;
		ObjectInputStream ois = null;
		String serialDataFileName = TemporaryStorage.getDataFileName(fileName);
		try {
			Object objRead = null;
			ois = new ObjectInputStream(new FileInputStream(fileName));
			objRead = ois.readObject();
			channel = (PlotDataProvider) objRead;
			channel.setStation(DataModule.getOrAddStation(channel.getStation().getName()));

		} catch (FileNotFoundException e) {
			lg.error(e);
		} catch (IOException e) {
			lg.error(e);
		} catch (ClassNotFoundException e) {
			lg.error(e);
		} finally {
			try {
				ois.close();
			} catch (IOException e) {
				// Do nothing
			}
		}
		return channel;
	}

	/**
	 * print debug output to the console
	 */
	public void printout() {
		System.out.println(toString());
	}

	/**
	 * get color to color traces in manual mode
	 */
	public Color getColor() {
		return manualColor;
	}

	/**
	 * set color to color traces in manual mode
	 */
	public void setColor(Color color) {
		this.manualColor = color;
	}

	/**
	 * @return the lastUsedPixelizeArguments
	 */
	protected synchronized PixelizeArguments getLastUsedPixelizeArguments() {
		return lastUsedPixelizeArguments;
	}

	/**
	 * @param lastUsedPixelizeArguments
	 *            the lastUsedPixelizeArguments to set
	 */
	protected synchronized void setLastUsedPixelizeArguments(PixelizeArguments lastUsedPixelizeArguments) {
		this.lastUsedPixelizeArguments = lastUsedPixelizeArguments;
	}

	/**
	 * Temporary class to accumulate slice statistics
	 * 
	 */
	private class SliceData {
		double	top					= Double.NEGATIVE_INFINITY; // max value for
																// slice
		double	bottom				= Double.POSITIVE_INFINITY; // min value for
																// slice
		double	sum					= 0.0;
		int		dataPointCount		= 0;
		int		segmentNumber		= -1;
		int		continueAreaNumber	= -1;
		int		rdpNumber			= -1;

		PlotDataPoint getPoint(SortedSet<EventWrapper> evts) {
			double mean = dataPointCount == 0.0 ? Double.POSITIVE_INFINITY : sum / new Double(dataPointCount);
			return new PlotDataPoint(top, bottom, mean, segmentNumber, continueAreaNumber, rdpNumber, evts);
		}
	}
}
