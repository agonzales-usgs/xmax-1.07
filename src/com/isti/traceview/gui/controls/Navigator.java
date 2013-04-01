package com.isti.traceview.gui.controls;

import java.util.Date;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyListener;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.GridLayout;

import javax.swing.JPanel;
import javax.swing.JButton;

import com.isti.traceview.TraceView;
import com.isti.traceview.common.TimeInterval;
import com.isti.traceview.gui.GraphPanel;
import com.isti.traceview.gui.GraphUtil;

/**
 * Set of navigation buttons to control traces into GraphPanel.
 * 
 * @author Max Kokoulin
 */
public class Navigator extends JPanel implements ActionListener {

	/**
	 * Available buttons enumeration
	 */
	public enum Buttons {
		/**
		 * Jump to starting of first trace, it placed on the left side of graph panel
		 */
		START,
		/**
		 * Jump to ending of last trace, it placed on the right side of graph panel
		 */
		END,
		/**
		 * Move left, on the window length * nearJumpRatio, default 0.15
		 */
		LEFT,
		/**
		 * Move right, on the window length * nearJumpRatio, default 0.15
		 */
		RIGHT,
		/**
		 * Move left, on the window length * farJumpRatio, default 1.0
		 */
		LEFT_FAR,
		/**
		 * Move right, on the window length * farJumpRatio, default 1.0
		 */
		RIGHT_FAR,
		/**
		 * Move left, to the beginning of previous unvisible segment
		 */
		LEFT_NEXT,
		/**
		 * Move right, to the beginning of next unvisible segment
		 */
		RIGHT_NEXT,
		/**
		 * Delete (i.e. unload) traces from selected channel views
		 */
		DELETE
	}

	private static final Dimension maxSize = new Dimension(350, 30);
	private final static Insets inset = new Insets(1, 1, 1, 1);
	private double nearJumpRatio = 0.15;
	private double farJumpRatio = 1.0;
	private GraphPanel graphPanel = null;

	/**
	 * @param graphPanel
	 *            Graph panel which this element controls
	 * @param buttons
	 *            Array of buttons to show, in the order as in the array
	 */
	public Navigator(GraphPanel graphPanel, Buttons[] buttons) {
		super();
		this.graphPanel = graphPanel;
		setPreferredSize(maxSize);
		setMaximumSize(maxSize);
		setLayout(new GridLayout(0, buttons.length));
		for (Buttons button: buttons) {
			JButton jbutton = null;
			switch (button) {
			case START:
				jbutton = GraphUtil.createGraphicalButton("left_all.gif", "|<-");
				jbutton.setToolTipText("JUMP to first data");
				break;
			case END:
				jbutton = GraphUtil.createGraphicalButton("right_all.gif", "->|");
				jbutton.setToolTipText("JUMP to last data");
				break;
			case LEFT:
				jbutton = GraphUtil.createGraphicalButton("left_arrow.gif", "<");
				jbutton.setToolTipText("Move Slightly Back in Time");
				break;
			case RIGHT:
				jbutton = GraphUtil.createGraphicalButton("right_arrow.gif", ">");
				jbutton.setToolTipText("Move Slightly Ahead in Time");
				break;
			case LEFT_FAR:
				jbutton = GraphUtil.createGraphicalButton("far_left_arrow.gif", "<--");
				if (farJumpRatio == 1.0) {
					jbutton.setToolTipText("Move One Screen Back in Time");
				} else {
					jbutton.setToolTipText("Move Far Back in Time");
				}
				break;
			case RIGHT_FAR:
				jbutton = GraphUtil.createGraphicalButton("far_right_arrow.gif", "-->");
				if (farJumpRatio == 1.0) {
					jbutton.setToolTipText("Move One Screen Ahead in Time");
				} else {
					jbutton.setToolTipText("Move Far Ahead in Time");
				}
				break;
			case LEFT_NEXT:
				jbutton = GraphUtil.createGraphicalButton("super_far_left_arrow.gif", "<--");
				jbutton.setToolTipText("JUMP Back to nearest channel ending");
				break;
			case RIGHT_NEXT:
				jbutton = GraphUtil.createGraphicalButton("super_far_right_arrow.gif", "-->");
				jbutton.setToolTipText("JUMP Ahead to nearest channel beginning");
				break;
			case DELETE:
				jbutton = GraphUtil.createGraphicalButton("delete.gif", "delete");
				jbutton.setToolTipText("Delete selected channels");
				break;
			}
			jbutton.addActionListener(this);
			jbutton.setMargin(inset);
			jbutton.setActionCommand(button.name());
			add(jbutton);
		}
	}

	public void actionPerformed(ActionEvent e) {
		JButton button = (JButton) e.getSource();
		long mainWingowRange = graphPanel.getTimeRange().getDuration();
		Buttons btn = Buttons.valueOf(button.getActionCommand());
		if(btn==Buttons.DELETE){
			TraceView.getDataModule().deleteChannels(graphPanel.getCurrentSelectedChannels());
		} else {
			graphPanel.setTimeRange(getNewInterval(btn));
		}
	}
	
	
	public TimeInterval getNewInterval(Buttons button){
		long start;
		long end;
		long mainWingowRange = graphPanel.getTimeRange().getDuration();
		switch (button) {
		case START:
			start = TraceView.getDataModule().getAllDataTimeInterval().getStart();
			return new TimeInterval(start, start + mainWingowRange);
		case END: 
			end = TraceView.getDataModule().getAllDataTimeInterval().getEnd();
			return new TimeInterval(end - mainWingowRange, end);
		case LEFT:
			start = graphPanel.getTimeRange().getStart() - (new Double(mainWingowRange * nearJumpRatio).longValue());
			return new TimeInterval(start, start + mainWingowRange);
		case RIGHT: 
			start = graphPanel.getTimeRange().getStart() + (new Double(mainWingowRange * nearJumpRatio).longValue());
			return new TimeInterval(start, start + mainWingowRange);
		case LEFT_FAR: 
			start = graphPanel.getTimeRange().getStart() - (new Double(mainWingowRange * farJumpRatio).longValue());
			return new TimeInterval(start, start + mainWingowRange);
		case RIGHT_FAR: 
			start = graphPanel.getTimeRange().getStart() + (new Double(mainWingowRange * farJumpRatio).longValue());
			return new TimeInterval(start, start + mainWingowRange);
		case LEFT_NEXT: 
			Date endDate = graphPanel.getNearestSegmentEnd(graphPanel.getTimeRange().getStartTime());
			if (endDate == null) {
				end = TraceView.getDataModule().getAllDataTimeInterval().getStart() + mainWingowRange;
			} else {
				end = endDate.getTime();
			}
			return new TimeInterval(end - mainWingowRange, end);
		case RIGHT_NEXT: 
			Date startDate = graphPanel.getNearestSegmentBegin(graphPanel.getTimeRange().getEndTime());
			if (startDate == null) {
				start = TraceView.getDataModule().getAllDataTimeInterval().getEnd() - mainWingowRange;

			} else {
				start = startDate.getTime();
			}
			return new TimeInterval(start, start + mainWingowRange);
		default:
			return null;
		}
	}

	/**
	 * Getter of nearJumpRatio, "near" multiplier (in window sizes) to move traces by LEFT and RIGHT
	 * buttons
	 */
	public double getNearJumpRatio() {
		return nearJumpRatio;
	}

	/**
	 * Setter of nearJumpRatio, "near" multiplier (in window sizes) to move traces by LEFT and RIGHT
	 * buttons
	 */
	public void setNearJumpRatio(double nearJumpRatio) {
		this.nearJumpRatio = nearJumpRatio;
	}
	
	/**
	 * Getter of farJumpRatio, "far" multiplier (in window sizes) to move traces by LEFT_FAR and
	 * RIGHT_FAR buttons
	 */
	public double getFarJumpRatio() {
		return farJumpRatio;
	}

	/**
	 * Setter of farJumpRatio, "far" multiplier (in window sizes) to move traces by LEFT_FAR and
	 * RIGHT_FAR buttons
	 */
	public void setFarJumpRatio(double farJumpRatio) {
		this.farJumpRatio = farJumpRatio;
	}
	
	public void addKeyListener(KeyListener l){
		for(Component comp: this.getComponents()){
			comp.addKeyListener(l);
		}
	}
}
