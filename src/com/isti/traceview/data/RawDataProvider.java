package com.isti.traceview.data;

import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

import org.apache.log4j.Logger;

import com.isti.traceview.TraceViewException;
import com.isti.traceview.common.Station;
import com.isti.traceview.common.TimeInterval;
import com.isti.traceview.processing.FilterFacade;
import com.isti.traceview.processing.IFilter;
import com.isti.traceview.processing.IstiUtilsMath;

import edu.iris.Fissures.Time;
import edu.iris.Fissures.IfNetwork.ChannelId;
import edu.iris.Fissures.IfNetwork.NetworkId;
import edu.iris.Fissures.IfTimeSeries.EncodedData;
import edu.iris.Fissures.model.MicroSecondDate;
import edu.iris.Fissures.model.SamplingImpl;
import edu.iris.dmc.seedcodec.SteimException;
import edu.iris.dmc.seedcodec.SteimFrameBlock;
import edu.sc.seis.fissuresUtil.mseed.FissuresConvert;
import edu.sc.seis.fissuresUtil.mseed.Recompress;
import edu.sc.seis.seisFile.mseed.DataRecord;
import edu.sc.seis.seisFile.mseed.SeedFormatException;

/**
 * Class for trace representation, holds raw trace data and introduces an abstract way to get it. Trace data here is list of {@link Segment}s.
 * 
 * @author Max Kokoulin
 */
public class RawDataProvider extends Channel implements Observer {

	private static Logger lg = Logger.getLogger(RawDataProvider.class);

	/**
	 * @uml.property name="rawData"
	 * @uml.associationEnd multiplicity="(0 -1)" ordering="true"
	 * @uml.association name="channel has rawData"
	 */
	protected List<SegmentCache> rawData;

	private boolean loadingStarted = false;
	private boolean loaded = false;
	private long endTime;

	//Multiplier to scale all data
	private double scale = 1.0;

	// Used to store dataStream file name and restore it after serialization
	private String serialFile = null;
	private transient BufferedRandomAccessFile serialStream = null;

	// ----------------------------------------------------------
	// -----For network loading----------------------------------
	private transient TimeInterval lastUsedTI = null;
	private boolean isNullAnswer = false;

	// ----------------------------------------------------------
	public RawDataProvider(String channelName, Station station, String networkName, String locationName) {
		super(channelName, station, networkName, locationName);
		rawData = new ArrayList<SegmentCache>();
	}

	public RawDataProvider() {
		super(null, null, null, null);
		rawData = new ArrayList<SegmentCache>();
	}

	/**
	 * @param endTime
	 *            the endTime to set Used for network channels before data retrieving
	 */
	public void setEndTime(long endTime) {
		this.endTime = endTime;
	}

	/**
	 * Getter of the property <tt>rawData</tt>
	 * 
	 * @return Returns all raw data this provider contains.
	 * @uml.property name="rawData"
	 */
	public List<Segment> getRawData() {
		lg.debug("RawDataProvider.getRawData: " + toString());
		List<Segment> ret = new ArrayList<Segment>();
		synchronized (rawData) {
			if (isNetworkDataProvider()) {
				Segment marker = rawData.get(0).getSegment();
				SourceSocket source = (SourceSocket) marker.getDataSource();
				ret = source.getRawData(this);
				if (ret.size() == 0) {
					for (SegmentCache sc : rawData) {
						ret.add(sc.getSegment());
					}
				}
			} else {
				for (SegmentCache sc : rawData) {
					ret.add(sc.getSegment());
				}
			}
			lg.debug("RawDataProvider.getRawData ended: " + toString());
			return ret;
		}
	}

	/**
	 * @return Returns one point for given time value, or Integer.MIN_VALUE if value not found
	 */
	public int getRawData(double time) {
		List<Segment> ret = getRawData(new TimeInterval(new Double(time).longValue(), new Double(time).longValue()));
		if (ret.size() > 0) {
			Segment segment = ret.get(0);
			int[] data = segment.getData(time, time).data;
			if (data.length > 0)
				return data[0];
			else
				return Integer.MIN_VALUE;
		} else
			return Integer.MIN_VALUE;
	}

	/**
	 * @return Returns the raw data this provider contains for the time window.
	 * @uml.property name="rawData"
	 */
	public List<Segment> getRawData(TimeInterval ti) {
		List<Segment> ret = null;
		lg.debug("RawDataProvider.getRawData: " + toString() + "; " + ti);
		synchronized (rawData) {
			if (isNetworkDataProvider()) {
				Segment marker = rawData.get(0).getSegment();
				SourceSocket source = (SourceSocket) marker.getDataSource();
				ret = source.getRawData(this, ti);
			} else {
				setLoaded(false);
				setLoadingStarted(true);
				// ret = Collections.synchronizedList(new ArrayList<Segment>());
				ret = new ArrayList<Segment>();
				for (SegmentCache sc : rawData) {
					Segment segment = sc.getSegment();
					if ((segment != null) && ti.isIntersect(segment.getTimeRange())) {
						ret.add(segment);
					}
				}
				setLoadingStarted(false);
				setLoaded(true);
			}
		}
		lg.debug("RawDataProvider.getRawData ended: " + toString() + "; " + ti + "; returned size " + (ret == null ? "null" : ret.size()));
		return ret;
	}

	public int getDataLength(TimeInterval ti) {
		int dataLength = 0;
		for (Segment segment : getRawData(ti)) {
			dataLength += segment.getData(ti).data.length;
		}
		return dataLength;
	}

	/**
	 * @return count of {@link Segment}s this provider contains
	 */
	public int getSegmentCount() {
		return rawData.size();
	}

	/**
	 * Add segment to raw data provider
	 * 
	 * @param segment
	 *            to add
	 */
	public void addSegment(Segment segment) {
		lg.debug("RawDataProvider.addSegment: " + toString() + "; " + segment);
		synchronized (rawData) {
			rawData.add(new SegmentCache(segment));
			segment.setRawDataProvider(this);
		}
		setSampleRate(segment.getSampleRate());
		lg.debug("RawDataProvider.addSegment ended:" + segment + " added to " + this);
	}

	/**
	 * @return time range of contained data
	 */
	public TimeInterval getTimeRange() {
		if (isNetworkDataProvider()) {
			return new TimeInterval(0L, Long.MAX_VALUE);
			// return new
			// TimeInterval(rawData.get(0).getSegment().getStartTime(), new
			// Date(endTime));
		} else {
			if (rawData.size() == 0) {
				return null;
			} else {
				return new TimeInterval(rawData.get(0).getSegment().getStartTime(), rawData.get(rawData.size() - 1).getSegment().getEndTime());
			}
		}
	}

	/**
	 * @return max raw data value on whole provider
	 */
	public int getMaxValue() {
		int ret = Integer.MIN_VALUE;
		for (Segment segment : getRawData()) {
			if (segment.getMaxValue() > ret) {
				ret = segment.getMaxValue();
			}
		}
		return ret;
	}

	/**
	 * @return min raw data value on whole provider
	 */
	public int getMinValue() {
		int ret = Integer.MAX_VALUE;
		for (Segment segment : getRawData()) {
			if (segment.getMinValue() < ret) {
				ret = segment.getMinValue();
			}
		}
		return ret;
	}

	/**
	 * clears this provider, drops all data
	 */
	public void drop() {
		synchronized (rawData) {
			for (SegmentCache sc : rawData) {
				sc.drop();
			}
			setLoaded(false);
		}
		setChanged();
		notifyObservers(getTimeRange());
	}

	/**
	 * @return flag if data loading process was started for this provider
	 */
	public boolean isLoadingStarted() {
		synchronized (rawData) {
			// lg.debug("RawDataProvider.isLoadingStarted " + this);
			return loadingStarted;
		}
	}

	public void setLoadingStarted(boolean loading) {
		synchronized (rawData) {
			lg.debug("RawDataProvider.setLoadingStarted " + loading + "; " + this);
			this.loadingStarted = loading;
		}
	}

	/**
	 * @return flag is data provider loaded
	 */
	public boolean isLoaded() {
		synchronized (rawData) {
			// lg.debug("RawDataProvider.isLoaded " + this);
			return loaded;
		}
	}

	public void setLoaded(boolean loaded) {
		synchronized (rawData) {
			lg.debug("RawDataProvider.setLoaded " + loaded + "; " + this);
			this.loaded = loaded;
		}
	}

	/**
	 * Load data into this data provider from data sources
	 * 
	 * @param ti
	 */
	public void loadData(TimeInterval ti) {
		lg.debug(this + ": data loading started");
		for (SegmentCache sc : rawData) {
			sc.getSegment().load();
		}
		lg.debug(this + ": data loading ended");
	}

	/**
	 * @return list of data sources
	 */
	public List<ISource> getSources() {
		List<ISource> ret = new ArrayList<ISource>();
		if (rawData != null) {
			for (SegmentCache sc : rawData) {
				ret.add(sc.getSegment().getDataSource());
			}
		}
		return ret;
	}

	/**
	 * @param date
	 * @return data source contains data on this time
	 */
	public ISource getSource(Date date) {
		ISource ret = null;
		if (rawData != null) {
			for (SegmentCache sc : rawData) {
				if ((sc.getSegment().getEndTime().getTime() <= date.getTime()) && (sc.getSegment().getStartTime().getTime() >= date.getTime())) {
					return sc.getSegment().getDataSource();
				}
			}
		}
		return ret;
	}

	/**
	 * Sets data stream to serialize this provider
	 * 
	 * @param dataStream
	 */
	public void setDataStream(Object dataStream) {
		try {
			if (dataStream == null) {
				try {
					this.serialStream.close();
					this.serialStream = null;
				} catch (IOException e) {
					// do nothing
				}
			} else {
				if (dataStream instanceof String) {
					this.serialFile = (String) dataStream;
				}
				BufferedRandomAccessFile raf = new BufferedRandomAccessFile(serialFile, "rw");
				raf.order(BufferedRandomAccessFile.BIG_ENDIAN);
				this.serialStream = raf;
			}
			for (SegmentCache sc : rawData) {
				sc.setDataStream(serialStream);
			}
		} catch (FileNotFoundException e) {
			lg.error(e);
		} catch (IOException e) {
			lg.error(e);
		}
	}

	/**
	 * @return data stream where this provider was serialized
	 */
	public BufferedRandomAccessFile getDataStream() {
		return serialStream;
	}

	/**
	 * @return flag if this provider was serialized
	 */
	public boolean isSerialized() {
		return serialStream == null;
	}

	/**
	 * Loads all data to this provider from it's data sources
	 */
	public void load() {
		lg.debug("RawDataProvider.load " + this);
		synchronized (rawData) {
			setLoadingStarted(true);
			loadData(null);
			setLoadingStarted(false);
			setLoaded(true);
			setChanged();
		}
		lg.debug("RawDataProvider.load ended" + this);
		notifyObservers(getTimeRange());
	}

	/**
	 * Loads data inside given time interval to this provider from it's data sources
	 */
	public void load(TimeInterval ti) {
		lg.debug("RawDataProvider.load " + this + "; " + ti);
		synchronized (rawData) {
			setLoadingStarted(true);
			loadData(ti);
			setLoadingStarted(false);
			setLoaded(true);
			setChanged();
		}
		lg.debug("RawDataProvider.load ended " + this + "; " + ti);
		notifyObservers(getTimeRange());
	}

	/**
	 * Dumps content of this provider in miniseed format
	 * 
	 * @param ds
	 *            stream to dump
	 * @param ti
	 *            content's time interval
	 * @throws IOException
	 */
	public void dumpMseed(DataOutputStream ds, TimeInterval ti, IFilter filter) throws IOException {
		for (Segment segment : getRawData(ti)) {
			int[] data = segment.getData(ti).data;
			if (filter != null) {
				data = new FilterFacade(filter, this).filter(data);
			}
			TimeInterval exportedRange = TimeInterval.getIntersect(ti, new TimeInterval(segment.getStartTime(), segment.getEndTime()));
			if (data.length > 0) {
				try {
					List lst = Recompress.steim1(data);

					EncodedData edata[] = new EncodedData[lst.size()];
					for (int i = 0; i < edata.length; i++) {
						SteimFrameBlock block = (SteimFrameBlock) lst.get(i);
						edata[i] = new EncodedData((short) 10, block.getEncodedData(), block.getNumSamples(), false);
					}
					Time channelStartTime = new Time(FissuresConvert.getISOTime(FissuresConvert.getBtime(new MicroSecondDate(exportedRange.getStartTime()))), 0);
					LinkedList<DataRecord> dataRecords = FissuresConvert.toMSeed(edata, new ChannelId(new NetworkId(getNetworkName(), channelStartTime), getStation().getName(),
							getLocationName(), getChannelName(), channelStartTime), new MicroSecondDate(exportedRange.getStartTime()), (SamplingImpl) new SamplingImpl(data.length,
							new edu.iris.Fissures.model.TimeInterval(new MicroSecondDate(exportedRange.getStartTime()), new MicroSecondDate(exportedRange.getEndTime()))), 1);

					for (DataRecord rec : dataRecords) {
						rec.write(ds);
					}
				} catch (SteimException e) {
					lg.error("Can't encode data: " + ti + ", " + this + e);
				} catch (SeedFormatException e) {
					lg.error("Can't encode data: " + ti + ", " + this + e);
				}
			}

		}
	}

	/**
	 * Dumps content of this provider in ASCII format
	 * 
	 * @param fw
	 *            writer to dump
	 * @param ti
	 *            content's time interval
	 * @throws IOException
	 */
	public void dumpASCII(FileWriter fw, TimeInterval ti, IFilter filter) throws IOException {
		int i = 1;
		for (Segment segment : getRawData(ti)) {
			Double sampleRate = segment.getSampleRate();
			long currentTime = Math.max(ti.getStart(), segment.getStartTime().getTime());
			int[] data = segment.getData(ti).data;
			if (filter != null) {
				data = new FilterFacade(filter, this).filter(data);
			}
			for (int value : data) {
				if (ti.isContain(currentTime)) {
					fw.write(i + " " + TimeInterval.formatDate(new Date(currentTime), TimeInterval.DateFormatType.DATE_FORMAT_NORMAL) + " " + value + "\n");
				}
				currentTime = new Double(currentTime + sampleRate).longValue();
			}
			i++;
		}
	}

	/**
	 * Dumps content of this provider in XML format
	 * 
	 * @param fw
	 *            writer to dump
	 * @param ti
	 *            content's time interval
	 * @throws IOException
	 */
	public void dumpXML(FileWriter fw, TimeInterval ti, IFilter filter) throws IOException {
		int i = 1;
		fw.write("<Trace network=\"" + getNetworkName() + "\" station=\"" + getStation().getName() + "\" location=\"" + getLocationName() + "\" channel=\"" + getChannelName()
				+ "\">\n");
		for (Segment segment : getRawData(ti)) {
			Double sampleRate = segment.getSampleRate();
			long currentTime = Math.max(ti.getStart(), segment.getStartTime().getTime());
			boolean segmentStarted = false;
			int[] data = segment.getData(ti).data;
			if (filter != null) {
				data = new FilterFacade(filter, this).filter(data);
			}
			for (int value : data) {
				if (ti.isContain(currentTime)) {
					if (!segmentStarted) {
						fw.write("<Segment start =\"" + TimeInterval.formatDate(new Date(currentTime), TimeInterval.DateFormatType.DATE_FORMAT_NORMAL) + "\" sampleRate = \""
								+ segment.getSampleRate() + "\">\n");
						segmentStarted = true;
					}
					fw.write("<Value>" + value + "</Value>\n");
				}
				currentTime = new Double(currentTime + sampleRate).longValue();
			}
			i++;
			fw.write("</Segment>\n");
		}
		fw.write("</Trace>\n");
	}

	/**
	 * Dumps content of this provider in SAC format
	 * 
	 * @param fw
	 *            writer to dump
	 * @param ti
	 *            content's time interval
	 * @throws IOException
	 */
	public void dumpSacAscii(DataOutputStream ds, TimeInterval ti, IFilter filter) throws IOException, TraceViewException {
		List<Segment> segments = getRawData(ti);
		if (segments.size() != 1) {
			throw new TraceViewException("You have gaps in the interval to import as SAC");
		}
		int intData[] = segments.get(0).getData(ti).data;
		long currentTime = Math.max(ti.getStart(), segments.get(0).getStartTime().getTime());
		if (filter != null) {
			intData = new FilterFacade(filter, this).filter(intData);
		}
		float[] floatData = new float[intData.length];
		for (int i = 0; i < intData.length; i++) {
			floatData[i] = new Integer(intData[i]).floatValue();
		}
		SacTimeSeriesASCII sacAscii = SacTimeSeriesASCII.getSAC(this, new Date(currentTime), floatData);
		sacAscii.writeHeader(ds);
		sacAscii.writeData(ds);
	}

	/**
	 * @return string representation of data provider in debug purposes
	 */
	public String toString() {
		return "RawDataProvider:" + super.toString();
	}

	/**
	 * Sorts data provider after loading
	 */
	public void sort() {
		Collections.sort(rawData);
		// setting channel serial numbers in segments
		Segment previousSegment = null;
		int segmentNumber = 0;
		int sourceNumber = 0;
		int continueAreaNumber = 0;
		for (Segment segment : getRawData()) {
			if (previousSegment != null) {
				if (Segment.isDataBreak(previousSegment.getEndTime().getTime(), segment.getStartTime().getTime(), segment.getSampleRate())) {
					segmentNumber++;
				}
				if (Segment.isDataGap(previousSegment.getEndTime().getTime(), segment.getStartTime().getTime(), segment.getSampleRate())) {
					continueAreaNumber++;
				}
				if (!previousSegment.getDataSource().equals(segment.getDataSource())) {
					sourceNumber++;
				}
			}
			previousSegment = segment;
			segment.setChannelSerialNumber(segmentNumber);
			segment.setSourceSerialNumber(sourceNumber);
			segment.setContinueAreaNumber(continueAreaNumber);
		}
	}

	/**
	 * Prints RawDataProvider content
	 */
	public void printout() {
		System.out.println("  " + toString());
		for (Segment segment : getRawData()) {
			System.out.println("    " + segment.toString());
		}
	}

	public boolean isNetworkDataProvider() {
		if (rawData.size() == 1) {
			Segment marker = rawData.get(0).getSegment();
			return marker.getSampleCount() == 0 && marker.getSampleRate() == 0.0 && marker.getStartOffset() == 0L && (marker.getDataSource() instanceof SourceSocket);
		} else {
			return false;
		}
	}

	@Override
	public void update(Observable arg0, Object loaded) {
		if (loaded instanceof Boolean) {
			setLoaded(((Boolean) loaded).booleanValue());
			setLoadingStarted(!((Boolean) loaded).booleanValue());
		}
	}

	/**
	 * Standard comparator - by start time
	 */
	public int compareTo(Object o) {
		if (this.equals(o))
			return 0;
		if (o == null) {
			return -1;
		}
		if (o instanceof RawDataProvider) {
			RawDataProvider rdd = (RawDataProvider) o;
			if (getTimeRange().getStart() > rdd.getTimeRange().getStart()) {
				return 1;
			} else if (getTimeRange().getStart() == rdd.getTimeRange().getStart()) {
				return getChannelName().compareTo(rdd.getChannelName());
			} else
				return -1;
		} else
			return -1;
	}

	/**
	 * Get name of file to serialize this RawDataProvider in the temporary storage
	 * 
	 * @return
	 */
	public String getSerialFileName() {
		return getName() + ".SER";
	}

	/**
	 * Special serialization handler
	 * 
	 * @param out
	 *            stream to serialize this object
	 * @see Serializable
	 * @throws IOException
	 */
	private void writeObject(ObjectOutputStream out) throws IOException {
		lg.debug("Serializing RawDataProvider" + toString());
		out.defaultWriteObject();
	}

	/**
	 * Special deserialization handler
	 * 
	 * @param in
	 *            stream to deserialize object
	 * @see Serializable
	 * @throws IOException
	 */
	private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		lg.debug("Deserializing RawDataProvider" + toString());
		if (serialFile != null) {
			serialStream = new BufferedRandomAccessFile(serialFile, "rw");
			serialStream.order(BufferedRandomAccessFile.BIG_ENDIAN);
			setDataStream(serialStream);
		}
		lg.debug("Deserialized RawDataProvider" + toString());
	}

	/**
	 * @return the lastUsedTI
	 */
	public synchronized TimeInterval getLastUsedTI() {
		return lastUsedTI;
	}

	/**
	 * @param lastUsedTI
	 *            the lastUsedTI to set
	 */
	public synchronized void setLastUsedTI(TimeInterval lastUsedTI) {
		this.lastUsedTI = lastUsedTI;
	}

	/**
	 * @return the isNullAnswer
	 */
	public synchronized boolean isNullAnswer() {
		return isNullAnswer;
	}

	/**
	 * @param isNullAnswer
	 *            the isNullAnswer to set
	 */
	public synchronized void setNullAnswer(boolean isNullAnswer) {
		this.isNullAnswer = isNullAnswer;
	}
	
	
	public List<SegmentData> getAdjustedData(TimeInterval ti) {
		List<Segment> segments = getRawData(ti);
		if (segments != null) {
			List<SegmentData> rd = new ArrayList<SegmentData>();
			for (int i = 0; i < segments.size(); i++) {
				Segment segment = segments.get(i);
				TimeInterval currentSegmentDataTI = TimeInterval.getIntersect(ti, new TimeInterval(segment.getStartTime(), segment.getEndTime()));
				SegmentData segmentData = segment.getData(currentSegmentDataTI);
				if (segmentData != null) {
					rd.add(segmentData);
				}
			}
			return rd;
		} else {
			return null;
		}
	}

	public List<int[]> getAdjustedIntData(TimeInterval ti) {
		List<SegmentData> segmentDataList = getAdjustedData(ti);
		if (segmentDataList != null) {
			List<int[]> rd = new ArrayList<int[]>();
			for (SegmentData segmentData: segmentDataList) {
				rd.add(segmentData.data);
			}
			return rd;
		} else {
			return null;
		}
	}

	/**
	 * @return the scale
	 */
	public double getScale() {
		return scale;
	}

	/**
	 * @param scale
	 *            the scale to set
	 */
	public void setScale(double scale) {
		this.scale = scale;
	}

	public void deleteRawData(TimeInterval ti) {
		lg.debug("RawDataProvider.deleteRawData: " + toString() + "; " + ti);
		synchronized (rawData) {
			if (isNetworkDataProvider()) {
				Segment marker = rawData.get(0).getSegment();
				SourceSocket source = (SourceSocket) marker.getDataSource();
				source.deleteRawData(this, ti);
			} else {
				setLoaded(false);
				setLoadingStarted(true);
				Iterator<SegmentCache> it = rawData.iterator();
				while(it.hasNext()){
					SegmentCache sc = it.next();
					Segment segment = sc.getSegment();
					if(ti.isContain(segment.getTimeRange())){
						sc.drop();
						it.remove();
					} else if(segment.getTimeRange().isContain(ti)){
						int index = rawData.indexOf(sc);
						sc.drop();
						it.remove();
						List<SegmentData> dataList = getAdjustedData(new TimeInterval(segment.getStartTime(), ti.getStartTime()));
						for(SegmentData segmentData: dataList){
							Segment newSeg = new Segment(this.getSource(new Date(segmentData.startTime)), segmentData);
							rawData.add(index, new SegmentCache(newSeg));
						}
						dataList = getAdjustedData(new TimeInterval( ti.getEndTime(),segment.getEndTime()));
						for(SegmentData segmentData: dataList){
							Segment newSeg = new Segment(this.getSource(new Date(segmentData.startTime)), segmentData);
							rawData.add(index+1, new SegmentCache(newSeg));
						}
					} else if(segment.getTimeRange().isIntersect(ti)){
						int index = rawData.indexOf(sc);
						sc.drop();
						it.remove();
						List<SegmentData> dataList = getAdjustedData(TimeInterval.getIntersect(segment.getTimeRange(), ti));
						for(SegmentData segmentData: dataList){
							Segment newSeg = new Segment(this.getSource(new Date(segmentData.startTime)), segmentData);
							rawData.add(index, new SegmentCache(newSeg));
						}
					}
				}
				setLoadingStarted(false);
				setLoaded(true);
			}
		}
		//lg.debug("RawDataProvider.deleteRawData ended: " + toString() + "; " + ti + "; returned size " + (ret == null ? "null" : ret.size()));

	}

	public double getMedian(TimeInterval ti) {
		List<int[]> rd = getAdjustedIntData(ti);
		if (rd == null || rd.size() == 0)
			return Double.NaN;
		int fullSize = 0;
		for (int[] d : rd) {
			fullSize += d.length;
		}
		int[] data = new int[fullSize];
		int i = 0;
		for (int[] d : rd) {
			for (int value : d) {
				data[i] = value;
				i++;
			}
		}
		return IstiUtilsMath.median(data) * getScale();
	}

	public double getStdDev(TimeInterval ti, double mean) {
		List<int[]> rd = getAdjustedIntData(ti);
		if (rd == null || rd.size() == 0)
			return Double.NaN;
		int fullSize = 0;
		double variance = 0;
		for (int[] d : rd) {
			fullSize += d.length;
			for (int value : d) {
				double diff = getScale() * value - mean;
				variance += diff * diff;
			}
		}
		return Math.sqrt(variance / fullSize);
	}

	/**
	 * internal class to hold original segment (cache(0)) and it's images processed by filters. Also may define caching policy.
	 */
	private class SegmentCache implements Serializable, Comparable {
		private Segment initialData;
		private transient List<Segment> filterCache;

		public SegmentCache(Segment segment) {
			initialData = segment;
			filterCache = new ArrayList<Segment>();
			;
		}

		/**
		 * Setter for raw data
		 * 
		 * @param segment
		 */
		public void setData(Segment segment) {
			initialData = segment;
			filterCache.clear();
		}

		/**
		 * Sets data stream to serialize this SegmentCache
		 * 
		 * @param dataStream
		 *            stream to serialize
		 */
		public void setDataStream(BufferedRandomAccessFile dataStream) {
			initialData.setDataStream(dataStream);
		}

		/**
		 * Getter for segment with raw, unprocessed data
		 * 
		 * @return
		 */
		public Segment getSegment() {
			return initialData;
		}

		/**
		 * Clears all data
		 */
		public void drop() {
			for (int i = 0; i < filterCache.size(); i++) {
				filterCache.remove(i);
			}
			this.getSegment().drop();
		}

		/**
		 * Standard comparator - by start time
		 */
		public int compareTo(Object o) {
			if (this.equals(o))
				return 0;
			if (o == null) {
				return -1;
			}
			if (o instanceof SegmentCache) {
				SegmentCache sc = (SegmentCache) o;
				if (getSegment().getStartTime().getTime() > sc.getSegment().getStartTime().getTime()) {
					return 1;
				} else if (getSegment().getStartTime().getTime() == sc.getSegment().getStartTime().getTime()) {
					return 0;
				}
				return -1;
			} else
				return -1;
		}
	}
}
