package com.isti.traceview.data;

import java.io.Serializable;
import java.util.Observer;
import java.util.Set;

/**
 * Interface to represent data source
 * 
 * @author Max Kokoulin
 */
public interface ISource extends Serializable {
	/**
	 * Enumeration for data source types
	 */
	public enum SourceType {
		/**
		 * File source
		 */
		FILE,
		/**
		 * Network socket source
		 */
		SOCKET

	};

	/**
	 * Enumeration for supported source formats
	 */
	public enum FormatType {
		MSEED, SEED, SAC, SEGY, SEGD, IMS, ASCII
	};

	/**
	 * @return Type of this source
	 */
	public SourceType getSourceType();

	/**
	 * @return Format of this source
	 */
	public FormatType getFormatType();

	/**
	 * Parse this data source, i.e scans it, determine which traces placed inside, filling metadata
	 * how we can find desired trace information using direct access method, see
	 * {@link ISource#load(long, int)}
	 * 
	 * @param dataModule
	 *            data module to store metadata
	 * @return list of found traces
	 */
	public Set<IChannel> parse(DataModule dataModule);
	
	public boolean isParsed();

	/**
	 * @return name of this data source
	 */
	public String getName();

	/**
	 * Load trace data from this data source
	 * 
	 * @param offset
	 *            offset where we starts
	 * @param sampleCount
	 *            how many points we want to load
	 * @return array of integers contains the data
	 */
	public void load(Segment segment);
	
	/**
	 * Get text representation of block header for given format
	 * 
	 * @param blockStartOffset
	 *            file pointer position to read block
	 */
	public String getBlockHeaderText(long blockStartOffset);
	
	//From Observable
	
	 public void addObserver(Observer o); 
     void clearChanged(); 
     public int countObservers(); 
     public void deleteObserver(Observer o);
     public void deleteObservers();
     public boolean hasChanged(); 
     public void notifyObservers(); 
     public void notifyObservers(Object arg); 
     abstract void setChanged();
}
