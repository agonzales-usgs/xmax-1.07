import java.util.List;

import javax.swing.JFrame;
import javax.swing.JOptionPane;

import org.apache.log4j.Logger;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import com.isti.jevalresp.RespUtils;
import com.isti.traceview.TraceViewException;
import com.isti.traceview.common.TimeInterval;
import com.isti.traceview.data.IChannel;
import com.isti.traceview.data.Response;
import com.isti.traceview.data.Response.FreqParameters;
import com.isti.traceview.processing.IFilter;
import com.isti.traceview.processing.ITransformation;
import com.isti.xmax.XMAXException;
import com.isti.xmax.gui.XMAXframe;

public class TransResp implements ITransformation {
	private static Logger lg = Logger.getLogger(TransResp.class);

	private static final double minFreqValue = 0.0001;
	private static final int numberFreqs = 500;

	public void transform(List<IChannel> input, TimeInterval ti, IFilter filter, Object configuration, JFrame parentFrame) {
		lg.debug("RESPONSE PLUGIN CALLED!!!!!!!!!!!!!!!!!!!");
		if (input.size() == 0) {
			JOptionPane.showMessageDialog(parentFrame, "Please select channels", "RESP computation warning", JOptionPane.WARNING_MESSAGE);
		} else {
			try {
				ViewResp vr = new ViewResp(parentFrame, createDataset(input, ti));
			} catch (XMAXException e) {
				JOptionPane.showMessageDialog(parentFrame, e.getMessage(), "Warning", JOptionPane.WARNING_MESSAGE);
			}
			catch (TraceViewException e) {
				JOptionPane.showMessageDialog(parentFrame, e.getMessage(), "Warning", JOptionPane.WARNING_MESSAGE);
			}
		}
		((XMAXframe)parentFrame).getGraphPanel().forceRepaint();
	}
	
	public void setMaxDataLength(int dataLength){

	}

	/**
	 * Creates a dataset, consisting of two series of cartesian data.
	 * 
	 * @return The dataset.
	 */

	private XYDataset createDataset(List<IChannel> input, TimeInterval ti) throws TraceViewException, XMAXException {
		XYSeriesCollection dataset = new XYSeriesCollection();
		for (IChannel channel: input) {
			XYSeries series = new XYSeries(channel.getName());
			double maxFreqValue = 500.0 / channel.getRawData().get(0).getSampleRate();
			final double sampFreq = (maxFreqValue - minFreqValue) / ((double) (numberFreqs - 1.0));
			FreqParameters fp =  new FreqParameters(minFreqValue, maxFreqValue, sampFreq, numberFreqs);		
			final double[] frequenciesArray = RespUtils.generateFreqArray(fp.startFreq, fp.endFreq, fp.numFreq, false);
			Response resp = channel.getResponse();
			if(resp == null) throw new XMAXException("Can't load response for channel " + channel.getName());
			final double respAmp[] = resp.getRespAmp(ti.getStartTime(), fp.startFreq, fp.endFreq, numberFreqs);			
			for (int i = 0; i < numberFreqs; i++) {
				series.add(Math.log10(frequenciesArray[i]), Math.log10(respAmp[i]));
			}
			dataset.addSeries(series);
		}
		return dataset;
	}
}
