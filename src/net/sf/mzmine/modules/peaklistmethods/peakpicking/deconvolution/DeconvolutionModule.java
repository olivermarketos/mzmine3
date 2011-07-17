/*
 * Copyright 2006-2011 The MZmine 2 Development Team
 * 
 * This file is part of MZmine 2.
 * 
 * MZmine 2 is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * MZmine 2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * MZmine 2; if not, write to the Free Software Foundation, Inc., 51 Franklin St,
 * Fifth Floor, Boston, MA 02110-1301 USA
 */

package net.sf.mzmine.modules.peaklistmethods.peakpicking.deconvolution;

import net.sf.mzmine.data.PeakList;
import net.sf.mzmine.main.MZmineCore;
import net.sf.mzmine.modules.MZmineModuleCategory;
import net.sf.mzmine.modules.MZmineProcessingModule;
import net.sf.mzmine.parameters.ParameterSet;
import net.sf.mzmine.taskcontrol.Task;

public class DeconvolutionModule implements MZmineProcessingModule {

	public static final String MODULE_NAME = "Chromatogram deconvolution";

	private ParameterSet parameters = new DeconvolutionParameters();

	/**
	 * @see net.sf.mzmine.modules.MZmineProcessingModule#toString()
	 */
	public String toString() {
		return MODULE_NAME;
	}

	/**
	 * @see net.sf.mzmine.modules.MZmineModule#getParameterSet()
	 */
	public ParameterSet getParameterSet() {
		return parameters;
	}

	public Task[] runModule(ParameterSet parameters) {

		PeakList peakLists[] = parameters.getParameter(
				DeconvolutionParameters.peakLists).getValue();

		// prepare a new group of tasks
		Task tasks[] = new DeconvolutionTask[peakLists.length];
		for (int i = 0; i < peakLists.length; i++) {
			tasks[i] = new DeconvolutionTask(peakLists[i], parameters);
		}

		MZmineCore.getTaskController().addTasks(tasks);

		return tasks;
	}

	public MZmineModuleCategory getModuleCategory() {
		return MZmineModuleCategory.PEAKLISTPICKING;
	}

}
