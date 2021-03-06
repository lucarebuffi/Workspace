package com.elettra.controller.driver.programs;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

import javax.swing.JPanel;

import com.elettra.common.io.CommunicationPortException;
import com.elettra.common.io.ICommunicationPort;
import com.elettra.controller.driver.commands.CommandParameters;
import com.elettra.controller.driver.commands.CommandsFacade;
import com.elettra.controller.driver.common.AxisConfiguration;
import com.elettra.controller.driver.common.ControllerPosition;
import com.elettra.controller.driver.common.DriverUtilities;
import com.elettra.controller.driver.common.MultipleAxis;
import com.elettra.controller.driver.listeners.MeasurePoint;
import com.elettra.controller.driver.listeners.Progress;
import com.elettra.controller.driver.programs.ProgramsFacade.Programs;

public class SCANProgram extends ShutterActivatorProgram
{
	public class DumpMeasure
	{
		public static final int	NO_DUMP					 = 0;
		public static final int	DUMP_EVERY_POINT = 1;
		public static final int	DUMP_AT_END			 = 2;
	}

	protected JPanel panel;
	protected int		 dump_measure;

	public SCANProgram(String programName)
	{
		super(programName);

		this.dump_measure = DumpMeasure.DUMP_EVERY_POINT;
	}

	public SCANProgram()
	{
		this(Programs.SCAN);
	}

	public ProgramResult execute(ProgramParameters parameters, ICommunicationPort port) throws CommunicationPortException
	{
		ScanResult result = new ScanResult();
		ScanParameters scanParameters = (ScanParameters) parameters;

		this.panel = scanParameters.getPanel();

		try
		{
			scanParameters.getListener().signalScanStart();

			AxisConfiguration axisConfiguration = DriverUtilities.getAxisConfigurationMap().getAxisConfiguration(scanParameters.getAxis());

			double signedStartPosition = DriverUtilities.controllerToNumber(new ControllerPosition(scanParameters.getStartSign(), scanParameters.getStartPosition()));
			double signedStopPosition = DriverUtilities.controllerToNumber(new ControllerPosition(scanParameters.getStopSign(), scanParameters.getStopPosition()));

			this.checkLimitations(port, scanParameters, axisConfiguration, signedStartPosition, signedStopPosition);

			// --------------------------------------------------------------------
			// initialization
			// --------------------------------------------------------------------

			double increment = Math.abs(signedStartPosition - signedStopPosition) / scanParameters.getNumberOfSteps();

			double scanInitialPosition = this.initializeScan(port, scanParameters, axisConfiguration, signedStartPosition, signedStopPosition);
			double scanActualPosition = signedStartPosition;

			// --------------------------------------------------------------------
			// scan cycle
			// --------------------------------------------------------------------

			this.openShutter();

			MeasureParameters countParameters = new MeasureParameters(scanParameters.getAxis(), scanParameters.getListener());
			countParameters.setScanTime(scanParameters.getScanTime());

			Progress progress = new Progress();

			BufferedWriter dumper = null;

			if (this.dump_measure != DumpMeasure.NO_DUMP)
				dumper = new BufferedWriter(new FileWriter("./data/lastscan.dump"));

			try
			{
				for (int scanIndex = 0; scanIndex < scanParameters.getNumberOfSteps() && !scanParameters.getListener().isStopScanActivated(scanParameters.getAxis()); scanIndex++)
				{
					progress.setProgress((int) ((((double) (scanIndex + 1)) / ((double) scanParameters.getNumberOfSteps())) * 100));
					this.doMeasure(port, scanParameters, result, scanInitialPosition, scanActualPosition, countParameters, progress, dumper);
					this.moveMotors(scanParameters, axisConfiguration, increment, scanActualPosition + increment, port);

					scanActualPosition += increment;
				}

				if (!scanParameters.getListener().isStopScanActivated(scanParameters.getAxis()))
					this.doMeasure(port, scanParameters, result, scanInitialPosition, scanActualPosition, countParameters, progress, dumper);
			}
			finally
			{
				if (dumper != null)
					dumper.close();
			}
		}
		catch (InterruptedException e)
		{
			throw new CommunicationPortException(e);
		}
		catch (IOException e)
		{
			throw new CommunicationPortException(e);
		}
		finally
		{
			try
			{
				this.closeShutter();
			}
			catch (IOException e)
			{
				throw new CommunicationPortException(e);
			}
			finally
			{
				scanParameters.getListener().signalScanStop();
			}
		}

		return result;
	}

	// --------------------------------------------------------------------
	//
	// PRIVATE METHODS
	//
	// --------------------------------------------------------------------

	private void checkLimitations(ICommunicationPort port, ScanParameters scanParameters, AxisConfiguration axisConfiguration, double signedStartPosition, double signedStopPosition) throws CommunicationPortException
	{
		if (axisConfiguration.isMultiple())
		{
			MultipleAxis axisNumbers = axisConfiguration.getMultipleAxis();

			AxisConfiguration axisConfiguration1 = DriverUtilities.getAxisConfigurationMap().getAxisConfiguration(axisNumbers.getAxis1());
			AxisConfiguration axisConfiguration2 = DriverUtilities.getAxisConfigurationMap().getAxisConfiguration(axisNumbers.getAxis2());

			double signedStartPosition1 = 0.0;
			double signedStopPosition1 = 0.0;
			double signedStartPosition2 = 0.0;
			double signedStopPosition2 = 0.0;

			if (axisNumbers.getDefaultReferenceAxis() == 2)
			{
				signedStartPosition1 = axisNumbers.getRelativeSign().sign() * signedStartPosition / 2.0;
				signedStopPosition1 = axisNumbers.getRelativeSign().sign() * signedStopPosition / 2.0;
				signedStartPosition2 = signedStartPosition;
				signedStopPosition2 = signedStopPosition;
			}
			else if (axisNumbers.getDefaultReferenceAxis() == 1)
			{
				signedStartPosition1 = signedStartPosition;
				signedStopPosition1 = signedStopPosition;
				signedStartPosition2 = axisNumbers.getRelativeSign().sign() * signedStartPosition * 2.0;
				signedStopPosition2 = axisNumbers.getRelativeSign().sign() * signedStopPosition * 2.0;
			}
			
			this.checkSingleAxisLimitations(port, axisNumbers.getAxis1(), scanParameters, axisConfiguration1, signedStartPosition1, signedStopPosition1);
			this.checkSingleAxisLimitations(port, axisNumbers.getAxis2(), scanParameters, axisConfiguration2, signedStartPosition2, signedStopPosition2);
		}
		else
		{
			this.checkSingleAxisLimitations(port, scanParameters.getAxis(), scanParameters, axisConfiguration, signedStartPosition, signedStopPosition);
		}

	}

	private void checkSingleAxisLimitations(ICommunicationPort port, int axis, ScanParameters scanParameters, AxisConfiguration axisConfiguration, double signedStartPosition, double signedStopPosition) throws CommunicationPortException
	{
		if (axisConfiguration.isBlocked())
			throw new IllegalStateException("Scan not Possible: Axis " + axisConfiguration.getName() + " is Blocked");

		if (axisConfiguration.isLimited())
		{
			if (scanParameters.getKindOfMovement().equals(DriverUtilities.getRelative()))
			{
				double currentPosition = DriverUtilities.parseAxisPositionResponse(axis, CommandsFacade.executeAction(CommandsFacade.Actions.REQUEST_AXIS_POSITION, new CommandParameters(axis, scanParameters.getListener()), port)).getSignedPosition();

				signedStartPosition += currentPosition;
				signedStopPosition += currentPosition;
			}

			if (signedStartPosition < axisConfiguration.getLimitDown() || signedStartPosition > axisConfiguration.getLimitUp())
				throw new IllegalArgumentException("Scan not Possible: Scan Initial Position (" + String.valueOf(signedStartPosition) +  ") lies outside limits for axis " + axisConfiguration.getName());

			if (signedStopPosition < axisConfiguration.getLimitDown() || signedStopPosition > axisConfiguration.getLimitUp())
				throw new IllegalArgumentException("Scan not Possible: Scan Final Position (" + String.valueOf(signedStopPosition) +  ") lies outside limits for axis " + axisConfiguration.getName());

		}
	}

	private void doMeasure(ICommunicationPort port, ScanParameters scanParameters, ScanResult result, double scanInitialPosition, double scanActualPosition, MeasureParameters countParameters, Progress progress, BufferedWriter dumper) throws IOException
	{
		MeasureResult measureResult = this.getMeasureFromDetector(port, countParameters);

		double measureXCoordinate = scanActualPosition;

		if (scanParameters.getKindOfMovement().equals(DriverUtilities.getRelative()))
			measureXCoordinate = scanActualPosition + scanInitialPosition;

		MeasurePoint measurePoint = new MeasurePoint(measureXCoordinate, measureResult.getMeasure(), measureResult.getAdditionalInformation1(), measureResult.getAdditionalInformation2());
		measurePoint.setCustomData(measureResult.getCustomData());

		result.addMeasurePoint(measurePoint);

		if (dumper != null)
		{
			dumper.write(String.format("%7.4f", measurePoint.getX()).trim() + " " + measurePoint.getMeasure() + " " + String.format("%17.14f", measurePoint.getAdditionalInformation1()) + " " + String.format("%17.14f", measurePoint.getAdditionalInformation2()));
			dumper.newLine();

			if (this.dump_measure == DumpMeasure.DUMP_EVERY_POINT)
				dumper.flush();
		}

		scanParameters.getListener().signalMeasure(scanParameters.getAxis(), measurePoint, progress, port);
	}

	protected MeasureResult getMeasureFromDetector(ICommunicationPort port, MeasureParameters measureParameters) throws CommunicationPortException
	{
		return (MeasureResult) ProgramsFacade.executeProgram(ProgramsFacade.Programs.COUNT, measureParameters, port);
	}

	private void moveMotors(ScanParameters scanParameters, AxisConfiguration axisConfiguration, double increment, double scanActualPosition, ICommunicationPort port) throws CommunicationPortException
	{
		ControllerPosition newPosition = scanParameters.getKindOfMovement().equals(DriverUtilities.getRelative()) ? new ControllerPosition(DriverUtilities.getPlus(), increment) : DriverUtilities.numberToController(scanActualPosition);

		if (axisConfiguration.isMultiple())
		{
			MultipleAxis axisNumbers = axisConfiguration.getMultipleAxis();

			MoveParameters axis1MoveParameters = new MoveParameters(axisNumbers.getAxis1(), scanParameters.getListener());
			axis1MoveParameters.setKindOfMovement(scanParameters.getKindOfMovement());

			MoveParameters axis2MoveParameters = new MoveParameters(axisNumbers.getAxis2(), scanParameters.getListener());
			axis2MoveParameters.setKindOfMovement(scanParameters.getKindOfMovement());

			if (axisNumbers.getDefaultReferenceAxis() == 2)
			{
				axis1MoveParameters.setPosition(newPosition.getAbsolutePosition() / 2.0);
				axis1MoveParameters.setSign(DriverUtilities.getSignProduct(newPosition.getSign(), axisNumbers.getRelativeSign()));
				axis2MoveParameters.setPosition(newPosition.getAbsolutePosition());
				axis2MoveParameters.setSign(newPosition.getSign());
			}
			else if (axisNumbers.getDefaultReferenceAxis() == 1)
			{
				axis1MoveParameters.setPosition(newPosition.getAbsolutePosition());
				axis1MoveParameters.setSign(newPosition.getSign());
				axis2MoveParameters.setPosition(newPosition.getAbsolutePosition() * 2.0);
				axis2MoveParameters.setSign(DriverUtilities.getSignProduct(newPosition.getSign(), axisNumbers.getRelativeSign()));
			}

			this.executeMoveProgram(port, axis2MoveParameters);
			CommandsFacade.waitForTheEndOfMovement(new CommandParameters(axisNumbers.getAxis2(), scanParameters.getListener()), port);

			this.executeMoveProgram(port, axis1MoveParameters);
			CommandsFacade.waitForTheEndOfMovement(new CommandParameters(axisNumbers.getAxis1(), scanParameters.getListener()), port);
		}
		else
		{
			MoveParameters axisMoveParameters = new MoveParameters(scanParameters.getAxis(), scanParameters.getListener());
			axisMoveParameters.setKindOfMovement(scanParameters.getKindOfMovement());
			axisMoveParameters.setPosition(newPosition.getAbsolutePosition());
			axisMoveParameters.setSign(newPosition.getSign());

			this.executeMoveProgram(port, axisMoveParameters);
			CommandsFacade.waitForTheEndOfMovement(new CommandParameters(scanParameters.getAxis(), scanParameters.getListener()), port);
		}
	}

	// --------------------------------------------------------------------

	private double initializeScan(ICommunicationPort port, ScanParameters scanParameters, AxisConfiguration axisConfiguration, double signedStartPosition, double signedStopPosition) throws CommunicationPortException
	{
		double scanInitialPosition = 0.0;

		if (axisConfiguration.isMultiple())
		{
			MultipleAxis axisNumbers = axisConfiguration.getMultipleAxis();

			if (axisNumbers.getDefaultReferenceAxis() == 1)
				scanInitialPosition = DriverUtilities.parseAxisPositionResponse(axisNumbers.getAxis1(), CommandsFacade.executeAction(CommandsFacade.Actions.REQUEST_AXIS_POSITION, new CommandParameters(axisNumbers.getAxis1(), scanParameters.getListener()), port)).getSignedPosition();
			else if (axisNumbers.getDefaultReferenceAxis() == 2)
				scanInitialPosition = DriverUtilities.parseAxisPositionResponse(axisNumbers.getAxis2(), CommandsFacade.executeAction(CommandsFacade.Actions.REQUEST_AXIS_POSITION, new CommandParameters(axisNumbers.getAxis2(), scanParameters.getListener()), port)).getSignedPosition();

			if (scanParameters.getKindOfMovement().equals(DriverUtilities.getAbsolute()))
				scanParameters.getListener().signalXAxisRange(scanParameters.getAxis(), signedStartPosition, signedStopPosition);
			else if (scanParameters.getKindOfMovement().equals(DriverUtilities.getRelative()))
				scanParameters.getListener().signalXAxisRange(scanParameters.getAxis(), scanInitialPosition + signedStartPosition, scanInitialPosition + signedStopPosition);

			MoveParameters axis1MoveParameters = new MoveParameters(axisNumbers.getAxis1(), scanParameters.getListener());
			axis1MoveParameters.setKindOfMovement(scanParameters.getKindOfMovement());

			MoveParameters axis2MoveParameters = new MoveParameters(axisNumbers.getAxis2(), scanParameters.getListener());
			axis2MoveParameters.setKindOfMovement(scanParameters.getKindOfMovement());
			axis2MoveParameters.setSign(scanParameters.getStartSign());

			if (axisNumbers.getDefaultReferenceAxis() == 2)
			{
				axis1MoveParameters.setPosition(scanParameters.getStartPosition() / 2.0);
				axis1MoveParameters.setSign(DriverUtilities.getSignProduct(scanParameters.getStartSign(), axisNumbers.getRelativeSign()));
				axis2MoveParameters.setPosition(scanParameters.getStartPosition());
				axis2MoveParameters.setSign(scanParameters.getStartSign());
			}
			else if (axisNumbers.getDefaultReferenceAxis() == 1)
			{
				axis1MoveParameters.setPosition(scanParameters.getStartPosition());
				axis1MoveParameters.setSign(scanParameters.getStartSign());
				axis2MoveParameters.setPosition(scanParameters.getStartPosition() * 2.0);
				axis2MoveParameters.setSign(DriverUtilities.getSignProduct(scanParameters.getStartSign(), axisNumbers.getRelativeSign()));
			}

			if (scanParameters.getKindOfMovement().equals(DriverUtilities.getAbsolute()))
			{
				if (signedStartPosition < signedStopPosition)
				{
					this.executeMoveProgram(port, axis1MoveParameters);
					CommandsFacade.waitForTheEndOfMovement(new CommandParameters(axisNumbers.getAxis1(), scanParameters.getListener()), port);

					this.executeMoveProgram(port, axis2MoveParameters);
					CommandsFacade.waitForTheEndOfMovement(new CommandParameters(axisNumbers.getAxis2(), scanParameters.getListener()), port);
				}
				else
				{
					this.executeMoveProgram(port, axis2MoveParameters);
					CommandsFacade.waitForTheEndOfMovement(new CommandParameters(axisNumbers.getAxis2(), scanParameters.getListener()), port);

					this.executeMoveProgram(port, axis1MoveParameters);
					CommandsFacade.waitForTheEndOfMovement(new CommandParameters(axisNumbers.getAxis1(), scanParameters.getListener()), port);
				}
			}
			else if (scanParameters.getKindOfMovement().equals(DriverUtilities.getRelative()))
			{
				if (scanParameters.getStartSign().equals(DriverUtilities.getMinus()))
				{
					this.executeMoveProgram(port, axis1MoveParameters);
					CommandsFacade.waitForTheEndOfMovement(new CommandParameters(axisNumbers.getAxis1(), scanParameters.getListener()), port);

					this.executeMoveProgram(port, axis2MoveParameters);
					CommandsFacade.waitForTheEndOfMovement(new CommandParameters(axisNumbers.getAxis2(), scanParameters.getListener()), port);
				}
				else if (scanParameters.getStartSign().equals(DriverUtilities.getPlus()))
				{
					this.executeMoveProgram(port, axis2MoveParameters);
					CommandsFacade.waitForTheEndOfMovement(new CommandParameters(axisNumbers.getAxis2(), scanParameters.getListener()), port);

					this.executeMoveProgram(port, axis1MoveParameters);
					CommandsFacade.waitForTheEndOfMovement(new CommandParameters(axisNumbers.getAxis1(), scanParameters.getListener()), port);
				}
				else
					throw new IllegalArgumentException("Start Sign not recognized:" + scanParameters.getStartSign());
			}
			else
				throw new IllegalArgumentException("Kind Of Movement not recognized:" + scanParameters.getKindOfMovement());
		}
		else
		{
			scanInitialPosition = DriverUtilities.parseAxisPositionResponse(scanParameters.getAxis(), CommandsFacade.executeAction(CommandsFacade.Actions.REQUEST_AXIS_POSITION, new CommandParameters(scanParameters.getAxis(), scanParameters.getListener()), port)).getSignedPosition();

			if (scanParameters.getKindOfMovement().equals(DriverUtilities.getAbsolute()))
				scanParameters.getListener().signalXAxisRange(scanParameters.getAxis(), signedStartPosition, signedStopPosition);
			else if (scanParameters.getKindOfMovement().equals(DriverUtilities.getRelative()))
				scanParameters.getListener().signalXAxisRange(scanParameters.getAxis(), scanInitialPosition + signedStartPosition, scanInitialPosition + signedStopPosition);

			MoveParameters axisMoveParameters = new MoveParameters(scanParameters.getAxis(), scanParameters.getListener());
			axisMoveParameters.setKindOfMovement(scanParameters.getKindOfMovement());
			axisMoveParameters.setPosition(scanParameters.getStartPosition());
			axisMoveParameters.setSign(scanParameters.getStartSign());

			this.executeMoveProgram(port, axisMoveParameters);
			CommandsFacade.waitForTheEndOfMovement(new CommandParameters(scanParameters.getAxis(), scanParameters.getListener()), port);
		}

		return scanInitialPosition;
	}

	protected void executeMoveProgram(ICommunicationPort port, MoveParameters axisMoveParameters) throws CommunicationPortException
	{
		ProgramsFacade.executeProgram(ProgramsFacade.Programs.MOVE, axisMoveParameters, port);
	}
}
