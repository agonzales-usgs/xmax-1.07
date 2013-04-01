package com.isti.traceview.data;


import com.isti.traceview.common.Station;
/**
 * Interface to represent factory class to produce plot data providers
 */
public interface IChannelFactory {	
	public IChannel getChannel(String channelName, Station station, String networkName, String locationName);
}
