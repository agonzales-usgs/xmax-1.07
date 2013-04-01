package com.isti.traceview.data;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;


import edu.sc.seis.seisFile.segd.ChannelSet;
import edu.sc.seis.seisFile.segd.ScanType;
import edu.sc.seis.seisFile.segd.SegdRecord;
import edu.sc.seis.seisFile.segd.Trace;


public class SourceFileSEGD extends SourceFile implements Serializable {
	private static Logger lg = Logger.getLogger(SourceFileSEGD.class);
	private final int NORM_AMPLITUDE =100000;
	
	public SourceFileSEGD(File file) {
		super(file);
		lg.debug("Created: " + this);
	}

	@Override
	public FormatType getFormatType() {
		return FormatType.SEGD;
	}
	
	@Override
	public Set<IChannel> parse(DataModule dataModule) {
		Set<IChannel> ret = new HashSet<IChannel>();
		try {
			SegdRecord segd = new SegdRecord(getFile());
			segd.readHeaders();
			for(ScanType st: segd.getScanTypes()){
				for(ChannelSet cs:st.getChannelSets()){
					for(Trace trace: cs.getTraces()){
						IChannel channel = dataModule.getOrAddChannel("Z",									//Channel 
								DataModule.getOrAddStation(new Double(trace.getReceiverLineNumber()).toString()),	//Station ID
								new Integer(segd.getManufacturerCode()).toString(),									//Network ID 
								new Double(trace.getReceiverPointNumber()).toString());								//Location
						ret.add(channel);
						Segment segment = new Segment(this, trace.getDataOffset(), trace.getTimeRange().getStartTime(), segd.getBaseScanInterval(), trace.getSamplesNumber(), 0);
						channel.addSegment(segment);						
					}
				}
			}

		} catch (IOException e) {
			lg.error("IO error: " + e);
		}
		return ret;
	}

	@Override
	public void load(Segment segment) {
		lg.debug("SourceFileSEGD.load(): " + this);
		int[] data = null;
		try {
			float[] traceData = Trace.getData(getFile(), segment.getStartOffset(), segment.getSampleCount());
			float maxDataValue = Float.MIN_VALUE;
			float minDataValue = Float.MAX_VALUE;
			for(float val:traceData){
				if(val>maxDataValue){
					maxDataValue = val;
				}
				if(val<minDataValue){
					minDataValue = val;
				}
			}
			double normCoeff = NORM_AMPLITUDE/(maxDataValue - minDataValue);
			data = new int[traceData.length];
			int i = 0;
			for (float val: traceData) {
				data[i++]=new Double(normCoeff*val).intValue();
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		for (int value: data) {
			segment.addDataPoint(value);
		}

	}


	
	public String toString() {
		return "SourceFileSEGD: file " + (getFile() == null ? "absent" : getFile().getName()) + ";";
	}
}
