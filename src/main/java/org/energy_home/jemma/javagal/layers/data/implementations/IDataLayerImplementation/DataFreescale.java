/**
 * This file is part of JEMMA - http://jemma.energy-home.org
 * (C) Copyright 2013 Telecom Italia (http://www.telecomitalia.it)
 *
 * JEMMA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License (LGPL) version 3
 * or later as published by the Free Software Foundation, which accompanies
 * this distribution and is available at http://www.gnu.org/licenses/lgpl.html
 *
 * JEMMA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License (LGPL) for more details.
 *
 */
package org.energy_home.jemma.javagal.layers.data.implementations.IDataLayerImplementation;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.energy_home.jemma.javagal.layers.PropertiesManager;
import org.energy_home.jemma.javagal.layers.business.GalController;
import org.energy_home.jemma.javagal.layers.business.Utils;
import org.energy_home.jemma.javagal.layers.data.implementations.SerialPortConnectorJssc;
import org.energy_home.jemma.javagal.layers.data.implementations.SerialPortConnectorRxTx;
import org.energy_home.jemma.javagal.layers.data.implementations.Utils.DataManipulation;
import org.energy_home.jemma.javagal.layers.data.interfaces.IConnector;
import org.energy_home.jemma.javagal.layers.data.interfaces.IDataLayer;
import org.energy_home.jemma.javagal.layers.object.ByteArrayObject;
import org.energy_home.jemma.javagal.layers.object.GatewayStatus;
import org.energy_home.jemma.javagal.layers.object.Mgmt_LQI_rsp;
import org.energy_home.jemma.javagal.layers.object.MyRunnable;
import org.energy_home.jemma.javagal.layers.object.ParserLocker;
import org.energy_home.jemma.javagal.layers.object.TypeMessage;
import org.energy_home.jemma.javagal.layers.object.WrapperWSNNode;
import org.energy_home.jemma.zgd.GatewayConstants;
import org.energy_home.jemma.zgd.GatewayException;
import org.energy_home.jemma.zgd.jaxb.APSMessage;
import org.energy_home.jemma.zgd.jaxb.APSMessageEvent;
import org.energy_home.jemma.zgd.jaxb.Address;
import org.energy_home.jemma.zgd.jaxb.Binding;
import org.energy_home.jemma.zgd.jaxb.BindingList;
import org.energy_home.jemma.zgd.jaxb.DescriptorCapability;
import org.energy_home.jemma.zgd.jaxb.Device;
import org.energy_home.jemma.zgd.jaxb.EnergyScanResult;
import org.energy_home.jemma.zgd.jaxb.EnergyScanResult.ScannedChannel;
import org.energy_home.jemma.zgd.jaxb.InterPANMessage;
import org.energy_home.jemma.zgd.jaxb.InterPANMessageEvent;
import org.energy_home.jemma.zgd.jaxb.LogicalType;
import org.energy_home.jemma.zgd.jaxb.MACCapability;
import org.energy_home.jemma.zgd.jaxb.NodeDescriptor;
import org.energy_home.jemma.zgd.jaxb.NodeServices;
import org.energy_home.jemma.zgd.jaxb.NodeServices.ActiveEndpoints;
import org.energy_home.jemma.zgd.jaxb.SecurityStatus;
import org.energy_home.jemma.zgd.jaxb.ServerMask;
import org.energy_home.jemma.zgd.jaxb.ServiceDescriptor;
import org.energy_home.jemma.zgd.jaxb.SimpleDescriptor;
import org.energy_home.jemma.zgd.jaxb.StartupAttributeInfo;
import org.energy_home.jemma.zgd.jaxb.Status;
import org.energy_home.jemma.zgd.jaxb.TxOptions;
import org.energy_home.jemma.zgd.jaxb.WSNNode;
import org.energy_home.jemma.zgd.jaxb.ZCLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Freescale implementation of {@link IDataLayer}.
 * 
 * @author "Ing. Marco Nieddu
 *         <a href="mailto:marco.nieddu@consoft.it ">marco.nieddu@consoft.it</a>
 *         or <a href="marco.niedducv@gmail.com ">marco.niedducv@gmail.com</a>
 *         from Consoft Sistemi S.P.A.<http://www.consoft.it>, financed by EIT
 *         ICT Labs activity SecSES - Secure Energy Systems (activity id 13030)"
 */
public class DataFreescale implements IDataLayer {

	Boolean destroy = new Boolean(false);

	ExecutorService executor = null;

	private GalController gal = null;

	private final int NUMBEROFRETRY = 5;

	private IConnector dongleRs232 = null;

	private static final Logger LOG = LoggerFactory.getLogger(DataFreescale.class);

	public Long INTERNAL_TIMEOUT;

	public final static short SIZE_ARRAY = 2048;

	private ArrayBlockingQueue<ByteArrayObject> dataFromSerialComm;

	private ArrayBlockingQueue<ByteArrayObject> getDataFromSerialComm() {
		return dataFromSerialComm;
	}

	private final List<ParserLocker> listLocker;

	private List<ParserLocker> getListLocker() {
		return listLocker;
	}

	/**
	 * Creates a new instance with a reference to the Gal Controller.
	 * 
	 * @param _gal
	 *          a reference to the Gal Controller.
	 * @throws Exception
	 *           if an error occurs.
	 */
	public DataFreescale(GalController galController) throws Exception {

		this.gal = galController;

		listLocker = Collections.synchronizedList(new LinkedList<ParserLocker>());

		dataFromSerialComm = new ArrayBlockingQueue<ByteArrayObject>(SIZE_ARRAY);
		/*
		 * We don't know in advance which comm library is installed into the system.
		 */
		boolean foundSerialLib = false;
		try {
			dongleRs232 = new SerialPortConnectorJssc(getGal().getPropertiesManager().getzgdDongleUri(),
					getGal().getPropertiesManager().getzgdDongleSpeed(), this);
			foundSerialLib = true;
		} catch (NoClassDefFoundError e) {
			LOG.warn("jSSC not found");
		}

		if (!foundSerialLib) {

			try { // then with jSSC
				dongleRs232 = new SerialPortConnectorRxTx(getGal().getPropertiesManager().getzgdDongleUri(),
						getGal().getPropertiesManager().getzgdDongleSpeed(), this);
				foundSerialLib = true;
			} catch (NoClassDefFoundError e) {
				LOG.warn("RxTx not found");
			}

		}

		if (!foundSerialLib) {
			throw new Exception("Error not found Rxtx or Jssc serial connector library");
		}

		RS232Filter.create(dongleRs232);

		INTERNAL_TIMEOUT = getGal().getPropertiesManager().getCommandTimeoutMS();
		executor = Executors.newFixedThreadPool(getGal().getPropertiesManager().getNumberOfThreadForAnyPool(), new ThreadFactory() {
			public Thread newThread(Runnable r) {
				return new Thread(r, "THPool-processMessages");
			}
		});

		if (executor instanceof ThreadPoolExecutor) {
			((ThreadPoolExecutor) executor).setKeepAliveTime(getGal().getPropertiesManager().getKeepAliveThread(), TimeUnit.MINUTES);
			((ThreadPoolExecutor) executor).allowCoreThreadTimeOut(true);
		}
	}

	public void initialize() {
		Thread thrAnalizer = new Thread() {
			public void run() {
				while (!getDestroy()) {
					try {
						ByteArrayObject z = null;
						z = getDataFromSerialComm().take();
						if (z.getArray() != null)
							processAllRaw(z);
					} catch (Exception e) {
						LOG.error("Error on processAllRaw:", e);

					}
				}
				LOG.debug("TH-MessagesAnalizer Stopped!");
			}
		};

		thrAnalizer.setName("TH-MessagesAnalizer");
		thrAnalizer.setPriority(Thread.MAX_PRIORITY);
		thrAnalizer.start();
	}

	private byte[] rawnotprocessed = new byte[0];

	private GalController getGal() {
		return gal;
	}

	private void processAllRaw(ByteArrayObject rawdataObject) {

		byte[] partialrawdata = rawdataObject.getArrayRealSize();
		byte[] fullrawdata = new byte[partialrawdata.length + rawnotprocessed.length];

		System.arraycopy(rawnotprocessed, 0, fullrawdata, 0, rawnotprocessed.length);
		System.arraycopy(partialrawdata, 0, fullrawdata, rawnotprocessed.length, partialrawdata.length);

		int offset = 0;
		boolean foundStart;

		while (fullrawdata.length > offset) {

			foundStart = false;
			for (; offset < fullrawdata.length; offset++) {
				if (fullrawdata[offset] == DataManipulation.SEQUENCE_START) {
					foundStart = true;
					break;
				}
			}

			if (!foundStart) {
				rawnotprocessed = new byte[0];
				return;
			}

			if (fullrawdata.length <= offset + 3) {
				break;
			}

			int payloadLenght = fullrawdata[offset + 3];
			int pdulength = DataManipulation.START_PAYLOAD_INDEX + payloadLenght + 1;

			if (fullrawdata.length - offset < pdulength) {
				break;
			}

			short localCfc = fullrawdata[offset + 1];
			localCfc ^= fullrawdata[offset + 2];
			localCfc ^= fullrawdata[offset + 3];

			for (int i = 0; i < payloadLenght; i++) {
				localCfc ^= fullrawdata[offset + DataManipulation.START_PAYLOAD_INDEX + i];
			}

			if (localCfc == fullrawdata[offset + DataManipulation.START_PAYLOAD_INDEX + payloadLenght]) {
				final byte[] toByteArray = new byte[pdulength - 1];
				System.arraycopy(fullrawdata, offset + 1, toByteArray, 0, pdulength - 1);
				final ByteArrayObject toProcess = new ByteArrayObject(toByteArray, toByteArray.length);
				try {
					executor.execute(new Runnable() {
						public void run() {
							try {
								processMessages(toProcess);
							} catch (Exception e) {
								LOG.error("Error on processMessages: {}", e);
							}
						}
					});
				} catch (Exception e) {
					LOG.error("during process", e);
				}
			} else {
				LOG.error("Checksum KO! Remove all raw bytes!");
				rawnotprocessed = new byte[0];
				return;
			}

			offset += DataManipulation.START_PAYLOAD_INDEX + payloadLenght + 1;
		}

		rawnotprocessed = new byte[fullrawdata.length - offset];
		System.arraycopy(fullrawdata, offset, rawnotprocessed, 0, fullrawdata.length - offset);

	}

	public void processMessages(ByteArrayObject frame) throws Exception {

		if (getGal().getPropertiesManager().getserialDataDebugEnabled()) {
			LOG.debug("Processing message: " + frame.toString());
		}

		short _command = (short) DataManipulation.toIntFromShort(frame.getArray()[0], frame.getArray()[1]);

		/* APSDE-DATA.Indication */
		if (_command == FreescaleConstants.APSDEDataIndication) {
			apsdeDataIndication(frame);
		}

		/* INTERPAN-DATA.Indication */
		else if (_command == FreescaleConstants.InterPANDataIndication) {
			interpanDataIndication(frame);
		}
		/* APSDE-DATA.Confirm */
		else if (_command == FreescaleConstants.APSDEDataConfirm) {
			apsdeDataConfirm(frame);
		}

		/* INTERPAN-Data.Confirm */
		else if (_command == FreescaleConstants.InterPANDataConfirm) {
			interpanDataConfirm(frame);
		}

		/* ZTC-Error.event */
		else if (_command == FreescaleConstants.ZTCErrorevent) {
			ztcErrorEvent(frame);
		}

		/* ZDP-Mgmt_Nwk_Update.Notify */

		else if (_command == FreescaleConstants.ZDPMgmt_Nwk_UpdateNotify) {
			zdpMgmtNwkUpdateNotify(frame);
		}

		/* ZDP-SimpleDescriptor.Response */
		else if (_command == FreescaleConstants.ZDPSimpleDescriptorResponse) {
			zdpSimpleDescriptorResponse(frame);

		}

		/* APS-GetEndPointIdList.Confirm */
		else if (_command == FreescaleConstants.APSGetEndPointIdListConfirm) {
			apsGetEndPointListConfirm(frame);
		}

		/* ZDP-BIND.Response */
		else if (_command == FreescaleConstants.ZDPMgmtBindResponse) {
			zdpBindResponse(frame);
		}

		/* ZDP-UNBIND.Response */
		else if (_command == FreescaleConstants.ZDPUnbindResponse) {
			zdpUnbindResponse(frame);
		}

		/* ZDP-Mgmt_Bind.Response */
		else if (_command == FreescaleConstants.ZDPMgmt_BindResponse) {
			zdpMgmtBindResponse(frame);
		}

		/* APS-DeregisterEndPoint.Confirm */
		else if (_command == FreescaleConstants.APSDeRegisterEndPointConfirm) {
			apsDeregisterEndPointConfirm(frame);

		}

		/* APS-ZDP-Mgmt_Lqi.Response */
		else if (_command == FreescaleConstants.ZDPMgmtLqiResponse) {
			short status = ((short) (frame.getArray()[3] & 0xFF));
			if (status == GatewayConstants.SUCCESS) {
				LOG.debug("Extracted ZDP-Mgmt_Lqi.Response with status[ {} ] ...waiting the related Indication ZDO:{} ", status,
						frame.toString());
			} else {
				LOG.error("Extracted ZDP-Mgmt_Lqi.Response with wrong status[" + status + "] " + frame.toString());

			}
		}

		/* ZTC-ReadExtAddr.Confirm */
		else if (_command == FreescaleConstants.ZTCReadExtAddrConfirm) {
			ztcReadExtAddrConfirm(frame);

		}

		/* ZDP-IEEE_addr.response */
		else if (_command == FreescaleConstants.ZDPIeeeAddrResponse) {
			zdpIeeeAddrResponse(frame);

		}
		/* ZDP-Mgmt_Leave.Response */
		else if (_command == FreescaleConstants.ZDPMgmtLeaveResponse) {
			LOG.debug("Extracted ZDP-Mgmt_Leave.Response: " + frame.toString());

		}
		/* ZDP-Active_EP_rsp.response */
		else if (_command == FreescaleConstants.ZDPActiveEpResponse) {
			zdpActiveEndPointResponse(frame);

		}

		/* ZDP-StopNwkEx.Confirm */
		else if (_command == FreescaleConstants.ZTCStopNwkExConfirm) {
			zdpStopNwkExConfirm(frame);

		}
		/* NLME-GET.Confirm */
		else if (_command == FreescaleConstants.NLMEGetConfirm) {
			nlmeGetConfirm(frame);

		}
		/* APSME_GET.Confirm */
		else if (_command == FreescaleConstants.APSMEGetConfirm) {
			apsmeGetConfirm(frame);

		}

		/* MacGetPIBAttribute.Confirm */
		else if (_command == FreescaleConstants.MacGetPIBAttributeConfirm) {
			MacGetConfirm(frame);
		}

		// ZDP-StartNwkEx.Confirm
		else if (_command == FreescaleConstants.ZTCStartNwkExConfirm) {
			zdpStartNwkExConfirm(frame);
		}

		/* APS-RegisterEndPoint.Confirm */
		else if (_command == FreescaleConstants.APSRegisterEndPointConfirm) {
			apsRegisterEndPointConfirm(frame);
		}
		/* ZTC-ModeSelect.Confirm */
		else if (_command == FreescaleConstants.ZTCModeSelectConfirm) {
			ztcModeSelectConfirm(frame);
		}
		/* MacGetPIBAttribute.Confirm */
		else if (_command == FreescaleConstants.MacGetPIBAttributeConfirm) {
			LOG.debug("Extracted MacGetPIBAttribute.Confirm: " + frame.toString());
		}
		/* MacBeaconNotify.Indication */
		else if (_command == FreescaleConstants.MacBeaconNotifyIndication) {
			LOG.debug("Extracted MacBeaconNotify.Indication: " + frame.toString());
		}
		/* MacBeaconStart.Indication */
		else if (_command == FreescaleConstants.MacPollNotifyIndication) {
			LOG.debug("Extracted MacBeaconStart.Indication: " + frame.toString());
		}
		/* NLME-NETWORK-FORMATION.Confirmn */
		else if (_command == FreescaleConstants.NLMENETWORKFORMATIONConfirm) {
			LOG.debug("Extracted NLME-NETWORK-FORMATION.Confirm: " + frame.toString());
		}
		/* NLME-START-ROUTER.Request */
		else if (_command == FreescaleConstants.NLMESTARTROUTERRequest) {
			LOG.debug("Extracted NLME-NETWORK-FORMATION.Confirm: " + frame.toString());
		}
		/* MacStart.Request */
		else if (_command == FreescaleConstants.MacStartRequest) {
			LOG.debug("Extracted MacStart.Request: {} ", frame.toString());
		}
		/* MacStart.Confirm */
		else if (_command == FreescaleConstants.MacStartConfirm) {
			LOG.debug("Extracted MacStart.Confirm: {}", frame.toString());
		}
		/* NLME-START-ROUTER.Confirm */
		else if (_command == FreescaleConstants.NLMESTARTROUTERConfirm) {
			LOG.info("Extracted NLME-START-ROUTER.Confirm: {}", frame.toString());
		}
		/* NWK-ProcessSecureFrame.Report */
		else if (_command == FreescaleConstants.NWKProcessSecureFrameReport) {
			LOG.debug("Extracted NWK-ProcessSecureFrame.Report: {}", frame.toString());
		}
		/* ZDP-Nwk-ProcessSecureFrame.Confirm */
		else if (_command == FreescaleConstants.ZDPNwkProcessSecureFrameConfirm) {
			LOG.debug("Extracted ZDP-Nwk-ProcessSecureFrame.Confirm: {}", frame.toString());
		}

		/* BlackBox.WriteSAS.Confirm */
		else if (_command == FreescaleConstants.BlackBoxWriteSASConfirm) {
			blackBoxWriteSASConfirm(frame);
		}
		/* ZTC-GetChannel.Confirm */
		else if (_command == FreescaleConstants.ZTCGetChannelConfirm) {
			ztcGetChannelConfirm(frame);
		}

		/* ZDP-NodeDescriptor.Response */
		else if (_command == FreescaleConstants.ZDPNodeDescriptorResponse) {
			zdpNodeDescriptorResponse(frame);
		}
		/* NMLE-SET.Confirm */
		else if (_command == FreescaleConstants.NMLESETConfirm) {
			nmleSetConfirm(frame);
		}
		/* APSME-SET.Confirm */
		else if (_command == FreescaleConstants.APSMESetConfirm) {
			apsmeSetConfirm(frame);
		}

		/* ZDP-Mgmt_Permit_Join.response */
		else if (_command == FreescaleConstants.ZDPMgmt_Permit_JoinResponse) {
			zdpMgmtPermitJoinResponse(frame);
		}

		/* APS-ClearDeviceKeyPairSet.Confirm */
		else if (_command == FreescaleConstants.APSClearDeviceKeyPairSetConfirm) {
			apsClearDeviceKeyPairSetConfirm(frame);

		}

		/* ZTC-ClearNeighborTableEntry.Confirm */
		else if (_command == FreescaleConstants.ZTCClearNeighborTableEntryConfirm) {
			ztcClearNeighborTableEntryConfirm(frame);

		}

		/* NLME-JOIN.Confirm */
		else if (_command == FreescaleConstants.NLMEJOINConfirm) {
			nlmeJoinConfirm(frame);
		}

		/* ZDO-NetworkState.Event */
		else if (_command == FreescaleConstants.ZDONetworkStateEvent) {
			zdoNetworkStateEvent(frame);
		}
		/* MacSetPIBAttribute.Confirm */
		else if (_command == FreescaleConstants.MacSetPIBAttributeConfirm) {
			LOG.debug("Extracted MacSetPIBAttribute.Confirm: {}", frame.toString());
		}
		/* NLME-ENERGY-SCAN.Request */
		else if (_command == FreescaleConstants.NLMEENERGYSCANRequest) {
			LOG.debug("Extracted NLME-ENERGY-SCAN.Request: {}", frame.toString());
		}
		/* MacScan.Request */
		else if (_command == FreescaleConstants.MacScanRequest) {
			LOG.debug("Extracted MacScan.Request: {}", frame.toString());
		}
		/* MacScan.Confirm */
		else if (_command == FreescaleConstants.MacScanConfirm) {
			LOG.debug("Extracted MacScan.Confirm: {}", frame.toString());
		}
		/* NLME-ENERGY-SCAN.confirm */
		else if (_command == FreescaleConstants.NLMEENERGYSCANconfirm) {
			LOG.debug("Extracted NLME-ENERGY-SCAN.confirm: {}", frame.toString());
		}
		/* NLME-NETWORK-DISCOVERY.Request */
		else if (_command == FreescaleConstants.NLMENETWORKDISCOVERYRequest) {
			LOG.debug("Extracted NLME-NETWORK-DISCOVERY.Request: {}", frame.toString());
		}
		/* MacScan.Request */
		else if (_command == FreescaleConstants.MacScanRequest) {
			LOG.debug("Extracted MacScan.Request: {}", frame.toString());
		}
		/* NLME-NETWORK-DISCOVERY.Confirm */
		else if (_command == FreescaleConstants.NLMENetworkDiscoveryConfirm) {
			LOG.debug("Extracted NLME-NETWORK-DISCOVERY.Confirm: {}", frame.toString());
		}
		/* NLME-NETWORK-FORMATION.Request */
		else if (_command == FreescaleConstants.NLMENETWORKFORMATIONRequest) {
			LOG.debug("Extracted NLME-NETWORK-FORMATION.Request: " + frame.toString());
		}
		/* NLME-SET.Request */
		else if (_command == FreescaleConstants.NLMESetRequest) {
			LOG.debug("Extracted NLME-SET.Request: " + frame.toString());
		}
		/* NLME-NWK-STATUS.Indication */
		else if (_command == FreescaleConstants.NLMENwkStatusIndication) {
			LOG.debug("NLME-NWK-STATUS.Indication: " + frame.toString());
		}
		/* NLME-ROUTE-DISCOVERY.confirm */
		else if (_command == FreescaleConstants.NLMENWKSTATUSIndication) {
			LOG.debug("NLME-ROUTE-DISCOVERY.confirm: " + frame.toString());
		}

	}

	/**
	 * @param message
	 * @throws Exception
	 */
	private void zdoNetworkStateEvent(ByteArrayObject message) throws Exception {
		short _status = (short) (message.getArray()[3] & 0xFF);
		switch (_status) {
		case 0x00:

			LOG.debug("Extracted ZDO-NetworkState.Event: DeviceInitialized (Device Initialized)");

			break;
		case 0x01:

			LOG.debug("Extracted ZDO-NetworkState.Event: DeviceinNetworkDiscoveryState (Device in Network Discovery State)");

			break;
		case 0x02:

			LOG.debug("Extracted ZDO-NetworkState.Event: DeviceJoinNetworkstate (Device Join Network state)");

			break;
		case 0x03:

			LOG.debug("Extracted ZDO-NetworkState.Event: DeviceinCoordinatorstartingstate (Device in Coordinator starting state)");

			getGal().setGatewayStatus(GatewayStatus.GW_STARTING);
			break;
		case 0x04:
			getGal().setGatewayStatus(GatewayStatus.GW_RUNNING);

			LOG.debug("ZDO-NetworkState.Event: DeviceinRouterRunningstate (Device in Router Running state)");

			break;
		case 0x05:
			getGal().setGatewayStatus(GatewayStatus.GW_RUNNING);
			LOG.debug("ZDO-NetworkState.Event: DeviceinEndDeviceRunningstate (Device in End Device Running state)");

			break;
		case 0x09:

			LOG.debug("Extracted ZDO-NetworkState.Event: Deviceinleavenetworkstate (Device in leave network state)");

			getGal().setGatewayStatus(GatewayStatus.GW_STOPPING);
			break;
		case 0x0A:

			LOG.debug("Extracted ZDO-NetworkState.Event: Deviceinauthenticationstate (Device in authentication state)");

			break;
		case 0x0B:

			LOG.debug("Extracted ZDO-NetworkState.Event: Deviceinstoppedstate (Device in stopped state)");
			getGal().setGatewayStatus(GatewayStatus.GW_STOPPED);

			break;
		case 0x0C:

			LOG.debug("Extracted ZDO-NetworkState.Event: DeviceinOrphanjoinstate (Device in Orphan join state)");
			break;
		case 0x10:

			getGal().setGatewayStatus(GatewayStatus.GW_RUNNING);
			LOG.debug("ZDO-NetworkState.Event: DeviceinCoordinatorRunningstate (Device is Coordinator Running state)");
			break;
		case 0x11:

			LOG.debug("Extracted ZDO-NetworkState.Event: DeviceinKeytransferstate (Device in Key transfer state)");
			break;
		case 0x12:

			LOG.debug("Extracted ZDO-NetworkState.Event: Deviceinauthenticationstate (Device in authentication state)");

			break;
		case 0x13:

			LOG.debug("Extracted ZDO-NetworkState.Event: DeviceOfftheNetwork (Device Off the Network)");

			break;
		default:
			throw new Exception("ZDO-NetworkState.Event: Invalid Status - " + _status);
		}
	}

	/**
	 * @param frame
	 * @throws Exception
	 */
	private void nlmeJoinConfirm(ByteArrayObject frame) throws Exception {
		short _status = (short) (frame.getArray()[8] & 0xFF);
		switch (_status) {
		case 0x00:
			LOG.debug("Extracted NLME-JOIN.Confirm: SUCCESS (Joined the network)");
			break;

		case 0xC2:
			LOG.debug("Extracted NLME-JOIN.Confirm: INVALID_REQUEST (Not Valid Request)");
			break;

		case 0xC3:
			LOG.debug("Extracted NLME-JOIN.Confirm: NOT_PERMITTED (Not allowed to join the network)");
			break;

		case 0xCA:
			LOG.debug("Extracted NLME-JOIN.Confirm: NO_NETWORKS (Network not found)");
			break;

		case 0x01:
			LOG.debug("Extracted NLME-JOIN.Confirm: PAN_AT_CAPACITY (PAN at capacity)");
			break;

		case 0x02:
			LOG.debug("Extracted NLME-JOIN.Confirm: PAN_ACCESS_DENIED (PAN access denied)");
			break;

		case 0xE1:
			LOG.debug("Extracted NLME-JOIN.Confirm: CHANNEL_ACCESS_FAILURE (Transmission failed due to activity on the channel)");
			break;

		case 0xE4:
			LOG.debug("Extracted NLME-JOIN.Confirm: FAILED_SECURITY_CHECK (The received frame failed security check)");
			break;

		case 0xE8:
			LOG.debug("Extracted NLME-JOIN.Confirm: INVALID_PARAMETER (A parameter in the primitive is out of the valid range)");
			break;

		case 0xE9:
			LOG.debug("Extracted NLME-JOIN.Confirm: NO_ACK (Acknowledgement was not received)");
			break;

		case 0xEB:
			LOG.debug("Extracted NLME-JOIN.Confirm: NO_DATA (No response data was available following a request)");
			break;

		case 0xF3:
			LOG.debug("Extracted NLME-JOIN.Confirm: UNAVAILABLE_KEY (The appropriate key is not available in the ACL)");
			break;

		case 0xEA:
			LOG.debug("Extracted NLME-JOIN.Confirm: NO_BEACON (No Networks)");
			break;

		default:
			throw new Exception("Extracted NLME-JOIN.Confirm: Invalid Status - " + _status);
		}

		LOG.debug("NLME-JOIN.Confirm: {}", frame.toString());
	}

	/**
	 * @param message
	 */
	private void ztcClearNeighborTableEntryConfirm(ByteArrayObject message) {
		LOG.debug("ZTC-ClearNeighborTableEntry.Confirm: {}", message.toString());
		short status = (short) (message.getArray()[3] & 0xFF);
		String mess = "";
		switch (status) {
		case 0x00:

			break;
		}
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.CLEAR_NEIGHBOR_TABLE_ENTRY) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {
					pl.getStatus().setCode(status);
					pl.getStatus().setMessage(mess);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void apsClearDeviceKeyPairSetConfirm(ByteArrayObject message) {
		LOG.debug("APS-ClearDeviceKeyPairSet.Confirm: {}", message.toString());

		short status = ((short) (message.getArray()[3] & 0xFF));
		String mess = "";
		/*
		 * switch (status) { case 0x00: break; }
		 */
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.CLEAR_DEVICE_KEY_PAIR_SET) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode(status);
					pl.getStatus().setMessage(mess);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}

		}
	}

	/**
	 * @param message
	 */
	private void zdpMgmtPermitJoinResponse(ByteArrayObject message) {
		LOG.debug("Extracted ZDP-Mgmt_Permit_Join.response: {} ", message.toString());
		short status = (short) (message.getArray()[3] & 0xFF);
		String mess = "";

		switch (status) {
		case 0x00:

			break;
		case 0x80:
			mess = "InvRequestType";
			break;
		case 0x84:
			mess = "Not Supported";
			break;
		case 0x87:
			mess = "Table Full";
			break;
		case 0x8D:
			mess = "NOT AUTHORIZED";
			break;
		case 0xC5:
			mess = "Already present in the network";
			break;

		}
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.PERMIT_JOIN) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode(status);
					pl.getStatus().setMessage(mess);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}

		}
	}

	/**
	 * @param message
	 */
	private void apsmeSetConfirm(ByteArrayObject message) {

		LOG.debug("Extracted APSME-SET.Confirm: {}", message.toString());

		short status = (short) (message.getArray()[3] & 0xFF);
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.APSME_SET) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode(status);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}

	}

	/**
	 * @param message
	 */
	private void nmleSetConfirm(ByteArrayObject message) {
		LOG.debug("Extracted NMLE-SET.Confirm: {}", message.toString());
		short status = (short) (message.getArray()[3] & 0xFF);
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.NMLE_SET) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {
					pl.getStatus().setCode(status);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}
	}

	/**
	 * @param message
	 * @throws Exception
	 */
	private void zdpNodeDescriptorResponse(ByteArrayObject message) throws Exception {
		int _NWKAddressOfInterest = DataManipulation.toIntFromShort(message.getArray()[5], message.getArray()[4]);
		Address _addressOfInterst = new Address();
		_addressOfInterst.setNetworkAddress(_NWKAddressOfInterest);
		NodeDescriptor _node = new NodeDescriptor();

		/* First Byte */
		byte _first = message.getArray()[6];
		byte _Logical_byte = (byte) (_first & 0x07);/* Bits 0,1,2 */
		byte _ComplexDescriptorAvalilable = (byte) ((_first & 0x08) >> 3);/* Bit3 */
		byte _UserDescriptorAvalilable = (byte) ((_first & 0x0A) >> 4);/* Bit4 */
		switch (_Logical_byte) {
		case FreescaleConstants.LogicalType.Coordinator:
			_node.setLogicalType(LogicalType.COORDINATOR);
			break;
		case FreescaleConstants.LogicalType.Router:
			_node.setLogicalType(LogicalType.ROUTER);
			break;
		case FreescaleConstants.LogicalType.EndDevice:
			_node.setLogicalType(LogicalType.END_DEVICE);
			break;
		default:
			throw new Exception("LogicalType is not valid value");
		}
		_node.setComplexDescriptorAvailable((_ComplexDescriptorAvalilable == 1 ? true : false));
		_node.setUserDescriptorAvailable((_UserDescriptorAvalilable == 1 ? true : false));

		/* Second Byte */
		byte _second = message.getArray()[7];
		/* Aps flags bits 0,1,2 */
		byte _FrequencyBand = (byte) ((_second
				& 0xF8) >> 0x03);/*
													 * bits 3 , 4 , 5 , 6 , 7
													 */
		switch (_FrequencyBand) {
		case 0x01:
			_node.setFrequencyBand("868MHz");
			break;
		case 0x04:
			_node.setFrequencyBand("900MHz");
			break;
		case 0x08:
			_node.setFrequencyBand("2400MHz");
			break;
		default:
			_node.setFrequencyBand("Reserved");
			break;
		}

		/* MACcapabilityFlags_BYTE Byte */
		byte _MACcapabilityFlags_BYTE = message.getArray()[8];
		MACCapability _maccapability = new MACCapability();
		byte _AlternatePanCoordinator = (byte) (_MACcapabilityFlags_BYTE
				& 0x01);/* Bit0 */
		byte _DeviceIsFFD = (byte) ((_MACcapabilityFlags_BYTE
				& 0x02) >> 1);/* Bit1 */
		byte _MainsPowered = (byte) ((_MACcapabilityFlags_BYTE
				& 0x04) >> 2);/* Bit2 */
		byte _ReceiverOnWhenIdle = (byte) ((_MACcapabilityFlags_BYTE
				& 0x08) >> 3);/* Bit3 */
		// bit 4-5 reserved
		byte _SecuritySupported = (byte) ((_MACcapabilityFlags_BYTE
				& 0x40) >> 6);/* Bit6 */
		byte _AllocateAddress = (byte) ((_MACcapabilityFlags_BYTE
				& 0x80) >> 7);/* Bit7 */
		_maccapability.setAlternatePanCoordinator((_AlternatePanCoordinator == 1 ? true : false));
		_maccapability.setDeviceIsFFD((_DeviceIsFFD == 1 ? true : false));
		_maccapability.setMainsPowered((_MainsPowered == 1 ? true : false));
		_maccapability.setReceiverOnWhenIdle((_ReceiverOnWhenIdle == 1 ? true : false));
		_maccapability.setSecuritySupported((_SecuritySupported == 1 ? true : false));
		_maccapability.setAllocateAddress((_AllocateAddress == 1 ? true : false));
		_node.setMACCapabilityFlag(_maccapability);

		/* ManufacturerCode_BYTES */
		int _ManufacturerCode_BYTES = DataManipulation.toIntFromShort(message.getArray()[10], message.getArray()[9]);
		_node.setManufacturerCode(_ManufacturerCode_BYTES);

		/* MaximumBufferSize_BYTE */
		short _MaximumBufferSize_BYTE = message.getArray()[11];
		_node.setMaximumBufferSize(_MaximumBufferSize_BYTE);

		/* MaximumTransferSize_BYTES */
		int _MaximumTransferSize_BYTES = DataManipulation.toIntFromShort(message.getArray()[13], message.getArray()[12]);
		_node.setMaximumIncomingTransferSize(_MaximumTransferSize_BYTES);

		/* ServerMask_BYTES */
		int _ServerMask_BYTES = DataManipulation.toIntFromShort(message.getArray()[15], message.getArray()[14]);
		ServerMask _serverMask = new ServerMask();
		byte _PrimaryTrustCenter = (byte) (_ServerMask_BYTES & 0x01);/* Bit0 */
		byte _BackupTrustCenter = (byte) ((_ServerMask_BYTES
				& 0x02) >> 1);/* Bit1 */
		byte _PrimaryBindingTableCache = (byte) ((_ServerMask_BYTES
				& 0x04) >> 2);/* Bit2 */
		byte _BackupBindingTableCache = (byte) ((_ServerMask_BYTES
				& 0x08) >> 3);/* Bit3 */
		byte _PrimaryDiscoveryCache = (byte) ((_ServerMask_BYTES
				& 0x10) >> 4);/* Bit4 */
		byte _BackupDiscoveryCache = (byte) ((_ServerMask_BYTES
				& 0x20) >> 5);/* Bit5 */
		_serverMask.setPrimaryTrustCenter((_PrimaryTrustCenter == 1 ? true : false));
		_serverMask.setBackupTrustCenter((_BackupTrustCenter == 1 ? true : false));
		_serverMask.setPrimaryBindingTableCache((_PrimaryBindingTableCache == 1 ? true : false));
		_serverMask.setBackupBindingTableCache((_BackupBindingTableCache == 1 ? true : false));
		_serverMask.setPrimaryDiscoveryCache((_PrimaryDiscoveryCache == 1 ? true : false));
		_serverMask.setBackupDiscoveryCache((_BackupDiscoveryCache == 1 ? true : false));
		_node.setServerMask(_serverMask);

		/* MaximumOutTransferSize_BYTES */
		int _MaximumOutTransferSize_BYTES = DataManipulation.toIntFromShort(message.getArray()[17], message.getArray()[16]);
		_node.setMaximumOutgoingTransferSize(_MaximumOutTransferSize_BYTES);

		/* CapabilityField_BYTES */
		byte _CapabilityField_BYTES = message.getArray()[18];
		DescriptorCapability _DescriptorCapability = new DescriptorCapability();
		byte _ExtendedActiveEndpointListAvailable = (byte) (_CapabilityField_BYTES
				& 0x01);/* Bit0 */
		byte _ExtendedSimpleDescriptorListAvailable = (byte) ((_CapabilityField_BYTES
				& 0x02) >> 1);/* Bit1 */
		_DescriptorCapability.setExtendedActiveEndpointListAvailable((_ExtendedActiveEndpointListAvailable == 1 ? true : false));
		_DescriptorCapability.setExtendedSimpleDescriptorListAvailable((_ExtendedSimpleDescriptorListAvailable == 1 ? true : false));
		_node.setDescriptorCapabilityField(_DescriptorCapability);
		String _key = String.format("%04X", _NWKAddressOfInterest);
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.NODE_DESCRIPTOR) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)
						&& (pl.get_Key().equalsIgnoreCase(_key))) {
					LOG.debug("@Extracted ZDP-NodeDescriptor.Response: {} -- KEY: {} ", message.toString(), _key);

					pl.getStatus()
							.setCode((short) (message.getArray()[3] & 0xFF));/* Status */
					pl.set_objectOfResponse(_node);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void ztcGetChannelConfirm(ByteArrayObject message) {
		LOG.debug("Extracted ZTC-GetChannel.Confirm: {}", message.toString());
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.CHANNEL_REQUEST) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					pl.set_objectOfResponse((short) message.getArray()[4]);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void blackBoxWriteSASConfirm(ByteArrayObject message) {
		LOG.debug("Extracted BlackBox.WriteSAS.Confirm: {}", message.toString());
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.WRITE_SAS) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {
					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void ztcModeSelectConfirm(ByteArrayObject message) {
		LOG.debug("Extracted ZTC-ModeSelect.Confirm: {}", message.toString());
		short status = (short) (message.getArray()[3] & 0xFF);
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.MODE_SELECT) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode(status);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void apsRegisterEndPointConfirm(ByteArrayObject message) {
		LOG.debug("Extracted APS-RegisterEndPoint.Confirm: {}", message.toString());
		// Found APS-RegisterEndPoint.Confirm. Remove the lock
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.CONFIGURE_END_POINT) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void zdpStartNwkExConfirm(ByteArrayObject message) {
		LOG.debug("Extracted ZDP-StartNwkEx.Confirm: {}", message.toString());
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.START_NETWORK) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {
					short status = (short) (message.getArray()[3] & 0xFF);
					if (status == 0x00) {
						getGal().setGatewayStatus(GatewayStatus.GW_STARTED);
					}

					pl.getStatus().setCode(status);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void apsmeGetConfirm(ByteArrayObject message) {
		LOG.debug("Extracted APSME_GET.Confirm: {}", message.toString());
		String key = String.format("%02X", (short) (message.getArray()[4]) & 0xFF);

		// Found APSME_GET-DATA.Confirm. Remove the lock
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.APSME_GET) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)
						&& (pl.get_Key().equalsIgnoreCase(key))) {
					short _Length = (short) DataManipulation.toIntFromShort(message.getArray()[9], message.getArray()[8]);
					byte[] _res = DataManipulation.subByteArray(message.getArray(), 10, _Length + 9);
					if (_Length >= 2)
						_res = DataManipulation.reverseBytes(_res);

					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					pl.set_objectOfResponse(DataManipulation.convertBytesToString(_res));
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void MacGetConfirm(ByteArrayObject message) {
		LOG.debug("Extracted MacGetPIBAttribute.Confirm: {}", message.toString());
		String key = String.format("%02X", (short) (message.getArray()[4] & 0xFF));
		// Found MacGetPIBAttribute.Confirm. Remove the lock
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.MAC_GET) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)
						&& (pl.get_Key().equalsIgnoreCase(key))) {
					short _Length = (short) DataManipulation.toIntFromShort(message.getArray()[9], message.getArray()[8]);
					byte[] _res = DataManipulation.subByteArray(message.getArray(), 10, _Length + 9);
					if (_Length >= 2)
						_res = DataManipulation.reverseBytes(_res);

					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					pl.set_objectOfResponse(DataManipulation.convertBytesToString(_res));
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void nlmeGetConfirm(ByteArrayObject message) {
		LOG.debug("Extracted NLME-GET.Confirm: {}", message.toString());
		String key = String.format("%02X", (message.getArray()[4] & 0xFF));
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				LOG.debug("NLME-GET.Confirm KEY: {} ---- {}", key, pl.get_Key());
				if ((pl.getType() == TypeMessage.NMLE_GET) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)
						&& (pl.get_Key().equalsIgnoreCase(key))) {
					short _Length = (short) DataManipulation.toIntFromShort(message.getArray()[9], message.getArray()[8]);
					byte[] _res = DataManipulation.subByteArray(message.getArray(), 10, _Length + 9);
					if (_Length >= 2)
						_res = DataManipulation.reverseBytes(_res);

					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					pl.set_objectOfResponse(DataManipulation.convertBytesToString(_res));
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void zdpStopNwkExConfirm(ByteArrayObject message) {
		LOG.debug("Extracted ZDP-StopNwkEx.Confirm: {}", message.toString());

		synchronized (getListLocker()) {

			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.STOP_NETWORK) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {
					short status = (short) (message.getArray()[3] & 0xFF);
					if (status == 0x00) {
						getGal().get_gatewayEventManager().notifyGatewayStopResult(
								makeStatusObject("The stop command has been processed byt ZDO with success.", (short) 0x00));
						synchronized (getGal()) {
							getGal().setGatewayStatus(GatewayStatus.GW_STOPPING);
						}
					}

					pl.getStatus().setCode(status);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void zdpActiveEndPointResponse(ByteArrayObject message) {
		LOG.debug("Extracted ZDP-Active_EP_rsp.response: {}", message.toString());
		short Status = (short) (message.getArray()[3] & 0xFF);
		Address _add = new Address();
		_add.setNetworkAddress(DataManipulation.toIntFromShort(message.getArray()[5], message.getArray()[4]));
		String Key = String.format("%04X", _add.getNetworkAddress());
		List<Short> _toRes = null;

		NodeServices _node = new NodeServices();
		_node.setAddress(_add);

		switch (Status) {
		case 0x00:
			_toRes = new ArrayList<Short>();
			int _EPCount = message.getArray()[6];

			for (int i = 0; i < _EPCount; i++) {
				_toRes.add((short) (message.getArray()[7 + i] & 0xFF));
				ActiveEndpoints _aep = new ActiveEndpoints();
				_aep.setEndPoint((short) (message.getArray()[7 + i] & 0xFF));
				_node.getActiveEndpoints().add(_aep);

			}

			LOG.debug("ZDP-Active_EP_rsp.response status: 00 - Success");
			break;

		case 0x80:
			LOG.debug("ZDP-Active_EP_rsp.response status: 80 - Inv_RequestType");
			break;

		case 0x89:
			LOG.debug("ZDP-Active_EP_rsp.response status: 89 - No_Descriptor");
			break;

		case 0x81:
			LOG.debug("ZDP-Active_EP_rsp.response status: 81 - Device_Not_found");
			break;
		}
		// Found ZDP-Active_EP_rsp.response. Remove the lock
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				/* DestAddress */
				if ((pl.getType() == TypeMessage.ACTIVE_EP) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)
						&& (pl.get_Key().equalsIgnoreCase(Key))) {

					pl.set_objectOfResponse(_toRes);
					pl.getStatus().setCode(Status);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void zdpIeeeAddrResponse(ByteArrayObject message) {
		LOG.debug("Extracted ZDP-IEEE_addr.response: {} ", message.toString());

		long longAddress = DataManipulation.toLong(message.getArray()[11], message.getArray()[10], message.getArray()[9],
				message.getArray()[8], message.getArray()[7], message.getArray()[6], message.getArray()[5], message.getArray()[4]);
		Integer shortAddress = DataManipulation.toIntFromShort(message.getArray()[13], message.getArray()[12]);

		String Key = String.format("%04X", shortAddress);

		// BigInteger _bi = BigInteger.valueOf(longAddress);
		BigInteger _bi = new BigInteger(1, Utils.longToByteArray(longAddress));
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.READ_IEEE_ADDRESS) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)
						&& (pl.get_Key().equalsIgnoreCase(Key))) {

					pl.set_objectOfResponse(_bi);
					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void ztcReadExtAddrConfirm(ByteArrayObject message) {
		LOG.debug("Extracted ZTC-ReadExtAddr.Confirm: {}", message.toString());
		long longAddress = DataManipulation.toLong(message.getArray()[11], message.getArray()[10], message.getArray()[9],
				message.getArray()[8], message.getArray()[7], message.getArray()[6], message.getArray()[5], message.getArray()[4]);
		// BigInteger _bi = BigInteger.valueOf(longAddress);
		BigInteger _bi = new BigInteger(1, Utils.longToByteArray(longAddress));
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.READ_EXT_ADDRESS) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.set_objectOfResponse(_bi);
					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void apsDeregisterEndPointConfirm(ByteArrayObject message) {
		LOG.debug("Extracted APS-DeregisterEndPoint.Confirm: {} ", message.toString());
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.DEREGISTER_END_POINT) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void zdpMgmtBindResponse(ByteArrayObject message) {
		LOG.debug("Extracted ZDP-Mgmt_Bind.Response: {}", message.toString());

		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.GET_BINDINGS) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					BindingList _res = new BindingList();

					if (pl.getStatus().getCode() == GatewayConstants.SUCCESS) {
						short length = (short) (message.getArray()[6] & 0xFF);
						int _index = 6;
						for (int i = 0; i < length; i++) {
							Binding _b = new Binding();
							long src_longAddress = DataManipulation.toLong(message.getArray()[_index + 8], message.getArray()[_index + 7],
									message.getArray()[_index + 6], message.getArray()[_index + 5], message.getArray()[_index + 4],
									message.getArray()[_index + 3], message.getArray()[_index + 2], message.getArray()[_index + 1]);
							short _srcEP = (short) (message.getArray()[_index + 9] & 0xFF);

							int _cluster = DataManipulation.toIntFromShort(message.getArray()[_index + 11], message.getArray()[_index + 10]);

							short _DestinationMode = (short) (message.getArray()[_index + 12] & 0xFF);
							Device _dev = new Device();

							if (_DestinationMode == 0x03) {

								long dst_longAddress = DataManipulation.toLong(message.getArray()[_index + 20], message.getArray()[_index + 19],
										message.getArray()[_index + 18], message.getArray()[_index + 17], message.getArray()[_index + 16],
										message.getArray()[_index + 15], message.getArray()[_index + 14], message.getArray()[_index + 13]);

								short _dstEP = (short) (message.getArray()[_index + 21] & 0xFF);
								// _dev.setAddress(BigInteger.valueOf(dst_longAddress));
								_dev.setAddress(new BigInteger(1, Utils.longToByteArray(dst_longAddress)));
								_dev.setEndpoint(_dstEP);
								_index = _index + 21;
							} else if (_DestinationMode == 0x01) {

								int _groupId = DataManipulation.toIntFromShort(message.getArray()[_index + 14], message.getArray()[_index + 13]);
								_dev.setAddress(BigInteger.valueOf(_groupId));
								_index = _index + 10;
							}
							_b.setClusterID(_cluster);
							_b.setSourceEndpoint(_srcEP);
							// _b.setSourceIEEEAddress(BigInteger.valueOf(src_longAddress));
							_b.setSourceIEEEAddress(new BigInteger(1, Utils.longToByteArray(src_longAddress)));
							_b.getDeviceDestination().add(_dev);
							_res.getBinding().add(_b);

						}
					}
					pl.set_objectOfResponse(_res);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void zdpUnbindResponse(ByteArrayObject message) {
		LOG.debug("Extracted ZDP-UNBIND.Response: {}", message.toString());

		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.REMOVE_BINDING) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					switch (pl.getStatus().getCode()) {
					case GatewayConstants.SUCCESS:

						break;

					case 0x84:
						pl.getStatus().setMessage("NOT_SUPPORTED (NOT SUPPORTED)");
						break;
					case 0x88:
						pl.getStatus().setMessage("No_Entry (No Entry)");
						break;
					case 0x8D:
						pl.getStatus().setMessage("NOT_AUTHORIZED (NOT AUTHORIZED");
						break;
					}
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void zdpBindResponse(ByteArrayObject message) {
		LOG.debug("Extracted ZDP-BIND.Response: {}", message.toString());

		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.ADD_BINDING) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					switch (pl.getStatus().getCode()) {
					case GatewayConstants.SUCCESS:

						break;

					case 0x84:
						pl.getStatus().setMessage("NOT_SUPPORTED (NOT SUPPORTED)");
						break;

					case 0x8C:
						pl.getStatus().setMessage("TABLE_FULL (TABLE FULL)");
						break;
					case 0x8D:
						pl.getStatus().setMessage("NOT_AUTHORIZED (NOT AUTHORIZED)");
						break;
					}
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void apsGetEndPointListConfirm(ByteArrayObject message) {
		LOG.debug("Extracted APS-GetEndPointIdList.Confirm: {}", message.toString());
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.GET_END_POINT_LIST) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					NodeServices _res = new NodeServices();
					if (pl.getStatus().getCode() == GatewayConstants.SUCCESS) {
						short length = message.getArray()[4];
						for (int i = 0; i < length; i++) {
							ActiveEndpoints _ep = new ActiveEndpoints();
							_ep.setEndPoint((short) (message.getArray()[5 + i] & 0xFF));
							_res.getActiveEndpoints().add(_ep);
						}
					}
					pl.set_objectOfResponse(_res);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void zdpSimpleDescriptorResponse(ByteArrayObject message) {
		LOG.debug("Extracted ZDP-SimpleDescriptor.Response: {}", message.toString());
		/* Address + EndPoint */
		Address _add = new Address();
		_add.setNetworkAddress(DataManipulation.toIntFromShort(message.getArray()[5], message.getArray()[4]));
		short EndPoint = (short) (message.getArray()[7] & 0xFF);
		String Key = String.format("%04X", _add.getNetworkAddress()) + String.format("%02X", EndPoint);
		// Found ZDP-SimpleDescriptor.Response. Remove the lock
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				/* Address + EndPoint */
				LOG.debug("ZDP-SimpleDescriptor.Response Sent Key: {} - Received Key: {}", pl.get_Key(), Key);

				if ((pl.getType() == TypeMessage.GET_SIMPLE_DESCRIPTOR) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)
						&& (pl.get_Key().equalsIgnoreCase(Key))) {

					pl.getStatus().setCode((short) (message.getArray()[3] & 0xFF));
					ServiceDescriptor _toRes = new ServiceDescriptor();
					if (pl.getStatus().getCode() == GatewayConstants.SUCCESS) {
						SimpleDescriptor _sp = new SimpleDescriptor();
						_sp.setApplicationProfileIdentifier(DataManipulation.toIntFromShort(message.getArray()[9], message.getArray()[8]));
						_sp.setApplicationDeviceIdentifier(DataManipulation.toIntFromShort(message.getArray()[11], message.getArray()[10]));
						_sp.setApplicationDeviceVersion((short) message.getArray()[12]);
						int _index = 14;
						short _numInpCluster = (short) (message.getArray()[13] & 0xFF);
						for (int i = 0; i < _numInpCluster; i++) {
							_sp.getApplicationInputCluster()
									.add(DataManipulation.toIntFromShort(message.getArray()[_index + 1], message.getArray()[_index]));
							_index = _index + 2;
						}

						short _numOutCluster = (short) (message.getArray()[_index++] & 0xFF);

						for (int i = 0; i < _numOutCluster; i++) {
							_sp.getApplicationOutputCluster()
									.add(DataManipulation.toIntFromShort(message.getArray()[_index + 1], message.getArray()[_index]));
							_index = _index + 2;
						}

						_toRes.setAddress(_add);
						_toRes.setEndPoint(EndPoint);
						_toRes.setSimpleDescriptor(_sp);

					}
					pl.set_objectOfResponse(_toRes);
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void zdpMgmtNwkUpdateNotify(ByteArrayObject message) {
		LOG.debug("Extracted ZDP-Mgmt_Nwk_Update.Notify: {}", message.toString());

		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.NWK_UPDATE) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					EnergyScanResult _result = new EnergyScanResult();

					int _address = DataManipulation.toIntFromShort(message.getArray()[4], message.getArray()[3]);

					short _status = (short) (message.getArray()[5] & 0xFF);
					if (_status == GatewayConstants.SUCCESS) {
						byte[] _scannedChannel = new byte[4];
						_scannedChannel[0] = message.getArray()[9];
						_scannedChannel[1] = message.getArray()[8];
						_scannedChannel[2] = message.getArray()[7];
						_scannedChannel[3] = message.getArray()[6];

						int _totalTrasmission = DataManipulation.toIntFromShort(message.getArray()[11], message.getArray()[10]);

						int _trasmissionFailure = DataManipulation.toIntFromShort(message.getArray()[13], message.getArray()[12]);

						short _scannedChannelListCount = (short) (message.getArray()[14] & 0xFF);
						for (int i = 0; i < _scannedChannelListCount; i++) {
							ScannedChannel _sc = new ScannedChannel();
							// _sc.setChannel(value)
							_sc.setEnergy(message.getArray()[15 + i]);

							_result.getScannedChannel().add(_sc);
						}

						pl.getStatus().setCode((short) (message.getArray()[7] & 0xFF));
						pl.set_objectOfResponse(_result);
						try {
							if (pl.getObjectLocker().size() == 0)
								pl.getObjectLocker().put((byte) 0);
						} catch (InterruptedException e) {

						}
					}
				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void ztcErrorEvent(ByteArrayObject message) {
		byte len = (byte) message.getArray()[2];
		String MessageStatus = "";
		if (len > 0) {
			short status = (short) (message.getArray()[3] & 0xFF);
			switch (status) {

			case 0x00:
				MessageStatus = "0x00: gSuccess_c (Should not be seen in this event.)";
				break;

			case 0xF4:
				MessageStatus = "0xF4: gZtcOutOfMessages_c (ZTC tried to allocate a message, but the allocation failed.)";
				break;

			case 0xF5:
				MessageStatus = "0xF5: gZtcEndPointTableIsFull_c (Self explanatory.)";
				break;

			case 0xF6:
				MessageStatus = "0xF6: gZtcEndPointNotFound_c (Self explanatory.)";
				break;

			case 0xF7:
				MessageStatus = "0xF7: gZtcUnknownOpcodeGroup_c (ZTC does not recognize the opcode group, and there is no application hook.)";
				break;

			case 0xF8:
				MessageStatus = "0xF8: gZtcOpcodeGroupIsDisabled_c (ZTC support for an opcode group is turned off by a compile option.)";
				break;

			case 0xF9:
				MessageStatus = "0xF9: gZtcDebugPrintFailed_c (An attempt to print a debug message ran out of buffer space.)";
				break;

			case 0xFA:
				MessageStatus = "0xFA: gZtcReadOnly_c (Attempt to set read-only data.)";
				break;

			case 0xFB:
				MessageStatus = "0xFB: gZtcUnknownIBIdentifier_c (Self explanatory.)";
				break;

			case 0xFC:
				MessageStatus = "0xFC: gZtcRequestIsDisabled_c (ZTC support for an opcode is turned off by a compile option.)";
				break;

			case 0xFD:
				MessageStatus = "0xFD: gZtcUnknownOpcode_c (Self expanatory.)";
				break;

			case 0xFE:
				MessageStatus = "0xFE: gZtcTooBig_c (A data item to be set or retrieved is too big for the buffer available to hold it.)";
				break;

			case 0xFF:
				MessageStatus = "0xFF: gZtcError_c (Non-specific, catchall error code.)";
				break;

			default:
				break;
			}
		}

		String logMessage = "Extracted ZTC-ERROR.Event Status: " + MessageStatus;
		LOG.error(logMessage + " from " + message.toString());
	}

	/**
	 * @param message
	 */
	private void interpanDataConfirm(ByteArrayObject message) {
		LOG.debug("Extracted INTERPAN-Data.Confirm: {}", message.toString());
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {

				if ((pl.getType() == TypeMessage.INTERPAN) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode((short) (message.getArray()[4] & 0xFF));
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}

				}
			}
		}
	}

	/**
	 * @param message
	 */
	private void apsdeDataConfirm(ByteArrayObject message) {

		/* DestAddress + DestEndPoint + SourceEndPoint */
		/* Marco Removed in order to increase the performance */

		long destAddress = DataManipulation.toLong(message.getArray()[11], message.getArray()[10], message.getArray()[9],
				message.getArray()[8], message.getArray()[7], message.getArray()[6], message.getArray()[5], message.getArray()[4]);
		short destEndPoint = ((short) (message.getArray()[12] & 0xFF));
		short sourceEndPoint = ((short) (message.getArray()[13] & 0xFF));
		String Key = String.format("%016X", destAddress) + String.format("%02X", destEndPoint) + String.format("%02X", sourceEndPoint);

		ParserLocker pl = new ParserLocker();

		// Found APSDE-DATA.Confirm. Remove the lock
		// synchronized (getListLocker()) {
		// for (ParserLocker pl : getListLocker()) {

		// if ((pl.getType() == TypeMessage.APS) && (pl.getStatus().getCode() ==
		// ParserLocker.INVALID_ID) && (pl.get_Key().equalsIgnoreCase(Key))) {

		pl.getStatus().setCode((short) (message.getArray()[14] & 0xFF));
		switch (pl.getStatus().getCode()) {
		case 0x00:
			pl.getStatus().setMessage("gSuccess (Success)");
			break;
		case 0x05:
			pl.getStatus().setMessage("gPartialSuccess (Partial Success)");
			break;
		case 0x07:
			pl.getStatus().setMessage("gSecurity_Fail (Security fail)");
			break;
		case 0x0A:
			pl.getStatus().setMessage("gApsInvalidParameter_c (Security fail)");
			break;
		case 0x04:
			pl.getStatus().setMessage("gZbNotOnNetwork_c (Transmitted the data frame)");
			break;
		case 0x01:
			pl.getStatus().setMessage("gApsIllegalDevice_c (Transmitted the data frame)");
			break;
		case 0x02:
			pl.getStatus().setMessage("gZbNoMem_c (Transmitted the data frame)");
			break;
		case 0xA0:
			pl.getStatus().setMessage("gApsAsduTooLong_c (ASDU too long)");
			break;
		case 0xA3:
			pl.getStatus().setMessage("gApsIllegalRequest_c (Invalid parameter)");
			break;
		case 0xA8:
			pl.getStatus().setMessage("gNo_BoundDevice (No bound device)");
			break;
		case 0xA9:
			pl.getStatus().setMessage("gNo_ShortAddress (No Short Address)");
			break;
		case 0xAE:
			pl.getStatus().setMessage("gApsTableFull_c (Aps Table Full)");
			break;
		case 0xC3:
			pl.getStatus().setMessage("INVALID_REQUEST (Not a valid request)");
			break;
		case 0xCC:
			pl.getStatus().setMessage("MAX_FRM_COUNTER (Frame counter has reached maximum value for outgoing frame)");
			break;
		case 0xCD:
			pl.getStatus().setMessage("NO_KEY (Key not available)");
			break;
		case 0xCE:
			pl.getStatus().setMessage("BAD_CCM_OUTPUT (Security engine produced erraneous output)");
			break;
		case 0xF1:
			pl.getStatus().setMessage("TRANSACTION_OVERFLOW (Transaction Overflow)");
			break;
		case 0xF0:
			pl.getStatus().setMessage("TRANSACTION_EXPIRED (Transaction Expired)");
			break;
		case 0xE1:
			pl.getStatus().setMessage(" CHANNEL_ACCESS_FAILURE (Key not available)");
			break;
		case 0xE6:
			pl.getStatus().setMessage("INVALID_GTS (Not valid GTS)");
			break;
		case 0xF3:
			pl.getStatus().setMessage("UNAVAILABLE_KEY (Key not found)");
			break;
		case 0xE5:
			pl.getStatus().setMessage("FRAME_TOO_LONG (Frame too long)");
			break;
		case 0xE4:
			pl.getStatus().setMessage("FAILED_SECURITY_CHECK (Failed security check)");
			break;
		case 0xE8:
			pl.getStatus().setMessage("INVALID_PARAMETER (Not valid parameter)");
			break;
		case 0xE9:
			pl.getStatus().setMessage("NO_ACK (Acknowledgement was not received)");
			break;
		}

		// no parenthe
		LOG.debug("Extracted APSDE-DATA.Confirm: {}", message.toString());
		LOG.debug("Status: {} --- Key: {}", pl.getStatus().getMessage(), Key);
	}

	private void interpanDataIndication(ByteArrayObject frame) {
		final InterPANMessageEvent messageEvent = new InterPANMessageEvent();
		short srcAddressMode = (short) (frame.getArray()[3] & 0xFF);
		messageEvent.setSrcAddressMode((long) srcAddressMode);
		messageEvent.setSrcPANID(DataManipulation.toIntFromShort(frame.getArray()[5], frame.getArray()[4]));

		BigInteger _ieee = null;
		Address address = new Address();

		switch (srcAddressMode) {
		case 0x00:
			// Reserved (No source address supplied)
			LOG.debug("Message Discarded: found reserved 0x00 as Source Address Mode ");
			// Error found, we don't proceed and discard the
			// message
			return;
		case 0x01:
			address.setNetworkAddress(DataManipulation.toIntFromShort(frame.getArray()[7], frame.getArray()[6]));
			try {
				_ieee = getGal().getIeeeAddress_FromShortAddress(address.getNetworkAddress());
			} catch (Exception e1) {
				LOG.error(e1.getMessage());
				return;
			}
			address.setIeeeAddress(_ieee);
			messageEvent.setSrcAddress(address);
			break;
		case 0x02:
			address.setNetworkAddress(DataManipulation.toIntFromShort(frame.getArray()[7], frame.getArray()[6]));
			try {
				_ieee = getGal().getIeeeAddress_FromShortAddress(address.getNetworkAddress());
			} catch (Exception e) {
				LOG.error(e.getMessage());
				return;
			}

			address.setIeeeAddress(_ieee);
			messageEvent.setSrcAddress(address);

			break;
		default:

			LOG.error("Message Discarded: not valid Source Address Mode");

			return;
		}

		short dstAddressMode = frame.getArray()[14];
		messageEvent.setDstAddressMode((long) dstAddressMode);
		messageEvent.setDstPANID(DataManipulation.toIntFromShort(frame.getArray()[16], frame.getArray()[15]));

		switch (dstAddressMode) {
		case 0x00:
			// Reserved (No source address supplied)
			LOG.debug("Message Discarded: found reserved 0x00 as Destination Address Mode ");
			// Error found, we don't proceed and discard the
			// message
			return;
		case 0x01:
			address.setNetworkAddress(DataManipulation.toIntFromShort(frame.getArray()[18], frame.getArray()[17]));
			try {
				_ieee = getGal().getIeeeAddress_FromShortAddress(address.getNetworkAddress());
			} catch (Exception e) {
				LOG.error(e.getMessage());
				return;
			}
			address.setIeeeAddress(_ieee);
			messageEvent.setDstAddress(address);
			break;
		case 0x02:
			address.setNetworkAddress(DataManipulation.toIntFromShort(frame.getArray()[18], frame.getArray()[17]));
			try {
				_ieee = getGal().getIeeeAddress_FromShortAddress(address.getNetworkAddress());
			} catch (Exception e) {
				LOG.error(e.getMessage());
				return;
			}
			address.setIeeeAddress(_ieee);
			messageEvent.setDstAddress(address);

			break;
		default:

			LOG.error("Message Discarded: not valid Destination Address Mode");

			return;
		}

		messageEvent.setProfileID(DataManipulation.toIntFromShort(frame.getArray()[20], frame.getArray()[19]));
		messageEvent.setClusterID(DataManipulation.toIntFromShort(frame.getArray()[22], frame.getArray()[21]));

		int asduLength = (frame.getArray()[23] & 0xFF);
		messageEvent.setASDULength(asduLength);
		messageEvent.setASDU(DataManipulation.subByteArray(frame.getArray(), 27, asduLength + 27));
		messageEvent.setLinkQuality((short) (frame.getArray()[asduLength + 28] & 0xFF));

		/* Gestione callback */
		getGal().getMessageManager().InterPANMessageIndication(messageEvent);
		getGal().get_gatewayEventManager().notifyInterPANMessageEvent(messageEvent);
	}

	private void apsdeDataIndication(ByteArrayObject frame) {

		LOG.debug("GAL-Received a apsdeDataIndication:" + frame.toString());

		WrapperWSNNode node = null;
		final APSMessageEvent messageEvent = new APSMessageEvent();
		messageEvent.setDestinationAddressMode((long) (frame.getArray()[3] & 0xFF));
		BigInteger _ieee = null;
		Address destinationAddress = new Address();

		switch (messageEvent.getDestinationAddressMode().shortValue()) {
		case 0x00:
			// Reserved (No source address supplied)
			LOG.debug("Message Discarded: found reserved 0x00 as Destination Address Mode ");
			return;
		case 0x01:
			// Value16bitgroupfordstAddr (DstEndpoint not
			// present)
			// No destination end point (so FF broadcast),
			// present
			// short
			// address on 2 bytes
			destinationAddress.setNetworkAddress(DataManipulation.toIntFromShort(frame.getArray()[5], frame.getArray()[4]));
			messageEvent.setDestinationAddress(destinationAddress);
			messageEvent.setDestinationEndpoint((short) 0xFF);

			break;
		case 0x02:
			// Value16bitAddrandDstEndpoint (16 bit address
			// supplied)
			destinationAddress.setNetworkAddress(DataManipulation.toIntFromShort(frame.getArray()[5], frame.getArray()[4]));
			messageEvent.setDestinationAddress(destinationAddress);
			messageEvent.setDestinationEndpoint((short) (frame.getArray()[6] & 0xFF));
			break;
		default:

			LOG.error("Message Discarded: not valid Destination Address Mode");

			return;
		}

		/**
		 * FIXME: error interpreting SRC address according to mode. Check APSDE-data
		 * indication format from specs: sourceAddressMode: 0x00 = reserved 0x01 =
		 * reserved 0x02 = 16-bit short address for SrcAddress and SrcEndpoint
		 * present 0x03 = 64-bit extended address for SrcAddress and SrcEndpoint
		 * present 0x04 – 0xff = reserved
		 */

		Address sourceAddress = new Address();
		messageEvent.setSourceAddressMode((long) (frame.getArray()[7] & 0xFF));

		switch (messageEvent.getSourceAddressMode().shortValue()) {

		case 0x00:
			// Reserved (No source address supplied)
			LOG.debug("Message Discarded: found reserved 0x00 as Destination Address Mode ");
			return;

		case 0x01:
			/*
			 * Value16bitgroupfordstAddr (DstEndpoint not present) No Source end point
			 * (so FF broadcast), present short address on 2 bytes
			 */

			sourceAddress.setNetworkAddress(DataManipulation.toIntFromShort(frame.getArray()[9], frame.getArray()[8]));

			messageEvent.setSourceAddress(sourceAddress);
			messageEvent.setSourceEndpoint((short) 0xFF);
			break;

		case 0x02:
			/*
			 * Value16bitAddrandDstEndpoint (16 bit address supplied)
			 */

			sourceAddress.setNetworkAddress(DataManipulation.toIntFromShort(frame.getArray()[9], frame.getArray()[8]));
			messageEvent.setSourceAddress(sourceAddress);
			messageEvent.setSourceEndpoint((short) (frame.getArray()[10] & 0xFF));
			break;

		default:
			LOG.error("Message Discarded: not valid Source Address Mode");
			return;
		}

		messageEvent.setProfileID(DataManipulation.toIntFromShort(frame.getArray()[12], frame.getArray()[11]));
		messageEvent.setClusterID(DataManipulation.toIntFromShort(frame.getArray()[14], frame.getArray()[13]));

		int lastAsdu = 16 + frame.getArray()[15] - 1;

		messageEvent.setData(DataManipulation.subByteArray(frame.getArray(), 16, lastAsdu));
		messageEvent.setAPSStatus((frame.getArray()[lastAsdu + 1] & 0xFF));

		boolean wasBroadcast = ((frame.getArray()[lastAsdu + 2] & 0xFF) == 0x01) ? true : false;

		/*
		 * ASK Jump WasBroadcast Security Status
		 */
		switch ((short) (frame.getArray()[lastAsdu + 3] & 0xFF)) {
		case 0x00:
			messageEvent.setSecurityStatus(SecurityStatus.UNSECURED);
			break;

		case 0x01:
			messageEvent.setSecurityStatus(SecurityStatus.SECURED_NWK_KEY);
			break;

		case 0x02:
			messageEvent.setSecurityStatus(SecurityStatus.SECURED_LINK_KEY);
			break;
		// ASK 0x03 not present on telecomitalia object
		default:
			LOG.debug("Message Discarded: not valid Security Status");

			// Error found, we don't proceed and discard the
			// message
			return;
		}

		messageEvent.setLinkQuality((short) (frame.getArray()[lastAsdu + 4] & 0xFF));

		messageEvent
				.setRxTime((long) DataManipulation.toIntFromShort(frame.getArray()[(lastAsdu + 8)], frame.getArray()[(lastAsdu + 5)]));

		LOG.debug("Extracted APSDE-DATA.Indication: {}", frame.toString());
		if ((getGal().getGatewayStatus() == GatewayStatus.GW_RUNNING) && getGal().get_GalNode() != null) {

			if (wasBroadcast) {
				LOG.info("BROADCAST MESSAGE");
			} else {

				node = updateNodeIfExist(messageEvent, messageEvent.getSourceAddress());

				if (node == null) {
					return;
				}

				synchronized (node) {
					Address address = node.get_node().getAddress();
					messageEvent.getSourceAddress()
							.setIeeeAddress(new BigInteger(1, Utils.longToByteArray(address.getIeeeAddress().longValue())));
					messageEvent.getSourceAddress().setNetworkAddress(new Integer(address.getNetworkAddress()));
				}

				if (messageEvent.getSourceAddress().getIeeeAddress() == null) {
					LOG.error("Message discarded IEEE source address not found for Short address:"
							+ String.format("%04X", messageEvent.getSourceAddress().getNetworkAddress()) + " -- ProfileID: "
							+ String.format("%04X", messageEvent.getProfileID()) + " -- ClusterID: "
							+ String.format("%04X", messageEvent.getClusterID()));
					return;
				}

				if (messageEvent.getSourceAddress().getNetworkAddress() == null) {
					LOG.error("Message discarded short source address not found for Ieee address:"
							+ String.format("%16X", messageEvent.getSourceAddress().getIeeeAddress()) + " -- ProfileID: "
							+ String.format("%04X", messageEvent.getProfileID()) + " -- ClusterID: "
							+ String.format("%04X", messageEvent.getClusterID()));
					return;
				}

			}
		} else
			return;
		if (messageEvent.getProfileID().equals(0)) {
			/*
			 * profileid == 0 ZDO Command
			 */
			if (messageEvent.getClusterID() == 0x8031) {
				Mgmt_LQI_rsp _res = new Mgmt_LQI_rsp(messageEvent.getData());
				String __key = String.format("%04X%02X", messageEvent.getSourceAddress().getNetworkAddress(), _res._StartIndex);
				LOG.debug("Received LQI_RSP from node: {} --Index: {}",
						String.format("%04X", messageEvent.getSourceAddress().getNetworkAddress()), String.format("%02X", _res._StartIndex));
				synchronized (getListLocker()) {
					for (ParserLocker pl : getListLocker()) {
						if ((pl.getType() == TypeMessage.LQI_REQ) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)
								&& (__key.equalsIgnoreCase(pl.get_Key()))) {

							pl.getStatus().setCode((short) (messageEvent.getAPSStatus() & 0xFF));
							pl.set_objectOfResponse(_res);
							try {
								if (pl.getObjectLocker().size() == 0)
									pl.getObjectLocker().put((byte) 0);
							} catch (InterruptedException e) {

							}
						}
					}
				}
			}

			if (getGal().getGatewayStatus() == GatewayStatus.GW_RUNNING) {
				getGal().getZdoManager().ZDOMessageIndication(messageEvent);
			}
		} else {
			// profileid > 0

			ZCLMessage zclMessage = new ZCLMessage();

			zclMessage.setAPSStatus(messageEvent.getAPSStatus());
			zclMessage.setClusterID(messageEvent.getClusterID());
			zclMessage.setDestinationEndpoint(messageEvent.getDestinationEndpoint());
			zclMessage.setProfileID(messageEvent.getProfileID());
			zclMessage.setRxTime(messageEvent.getRxTime());
			zclMessage.setSourceAddress(messageEvent.getSourceAddress());
			zclMessage.setSourceAddressMode(messageEvent.getSourceAddressMode());
			zclMessage.setSourceEndpoint(messageEvent.getSourceEndpoint());

			byte[] data = messageEvent.getData();

			// ZCL Header
			// Frame control 8bit
			// Manufacturer code 0/16bits
			// Transaction sequence number 8bit
			// Command identifier 8 bit

			ByteArrayObject _header = new ByteArrayObject(true);
			ByteArrayObject _payload = new ByteArrayObject(true);

			if ((data[0] & 0x04) == 0x04)/* Check manufacturer code */
			{
				_header.addByte(data[0]);// Frame control
				_header.addByte(data[1]);// Manufacturer Code(1/2)
				_header.addByte(data[2]);// Manufacturer Code(2/2)
				_header.addByte(data[3]);// Transaction sequence
				// number
				_header.addByte(data[4]);// Command Identifier
				for (int i = 5; i < data.length; i++)
					_payload.addByte(data[i]);
			} else {
				_header.addByte(data[0]);// Frame control
				_header.addByte(data[1]);// Transaction sequence
				// number
				_header.addByte(data[2]);// Command Identifier
				for (int i = 3; i < data.length; i++)
					_payload.addByte(data[i]);
			}

			zclMessage.setZCLHeader(_header.getArrayRealSize());
			zclMessage.setZCLPayload(_payload.getArrayRealSize());

			if (getGal().getGatewayStatus() == GatewayStatus.GW_RUNNING) {
				getGal().get_gatewayEventManager().notifyZCLEvent(zclMessage);
				getGal().getApsManager().APSMessageIndication(messageEvent);
				getGal().getMessageManager().APSMessageIndication(messageEvent);
			}
		}
	}

	private WrapperWSNNode updateNodeIfExist(final APSMessageEvent messageEvent, Address address) {

		WrapperWSNNode Wrapnode = new WrapperWSNNode(getGal(), String.format("%04X", address.getNetworkAddress()));
		WSNNode node = new WSNNode();
		node.setAddress(address);
		Wrapnode.set_node(node);
		if ((Wrapnode = getGal().getFromNetworkCache(Wrapnode)) != null) {
			synchronized (Wrapnode) {
				if (Wrapnode.is_discoveryCompleted()) {

					/* The node is already into the DB */
					if (getGal().getPropertiesManager().getKeepAliveThreshold() > 0) {
						if (!Wrapnode.isSleepyOrEndDevice()) {
							Wrapnode.reset_numberOfAttempt();
							Wrapnode.setTimerFreshness(getGal().getPropertiesManager().getKeepAliveThreshold());
							LOG.debug("Postponing  timer Freshness for Aps.Indication for node: {}",
									String.format("%04X", Wrapnode.get_node().getAddress().getNetworkAddress()));
						}
					}
				}
			}
		} else {

			PropertiesManager configuration = getGal().getPropertiesManager();

			if (address.getNetworkAddress() != 0xFFFF) {
				// 0x8034 is a LeaveAnnouncement, 0x0013 is a
				// DeviceAnnouncement, 0x8001 is a IEEE_Addr_Rsp

				if ((configuration.getAutoDiscoveryUnknownNodes() > 0) && (!(messageEvent.getProfileID() == 0x0000
						&& (messageEvent.getClusterID() == 0x0013 || messageEvent.getClusterID() == 0x8034
								|| messageEvent.getClusterID() == 0x8001 || messageEvent.getClusterID() == 0x8031)))) {

					if (address.getNetworkAddress().intValue() != getGal().get_GalNode().get_node().getAddress().getNetworkAddress()
							.intValue()) {

						/*
						 * Insert the node into cache, but with the discovery_completed flag
						 * a false
						 */

						WrapperWSNNode o = new WrapperWSNNode(getGal(), String.format("%04X", address.getNetworkAddress()));
						WSNNode _newNode = new WSNNode();
						o.set_discoveryCompleted(false);
						_newNode.setAddress(address);
						o.set_node(_newNode);

						if (LOG.isDebugEnabled()) {

							// FIXME Use Utils.toString(Address) to print the address.

							String shortAdd = (o.get_node().getAddress().getNetworkAddress() != null)
									? String.format("%04X", o.get_node().getAddress().getNetworkAddress())
									: "NULL";

							String IeeeAdd = (o.get_node().getAddress().getIeeeAddress() != null)
									? String.format("%08X", o.get_node().getAddress().getIeeeAddress())
									: "NULL";

							LOG.debug("Adding node from [AutoDiscovery Nodes] into the NetworkCache IeeeAddress: {} --- Short: {}", IeeeAdd,
									shortAdd);
						}
						getGal().getNetworkcache().add(o);

						Runnable thr = new MyRunnable(o) {
							@Override
							public void run() {
								WrapperWSNNode _newWrapperNode = (WrapperWSNNode) this.getParameter()[0];

								if ((_newWrapperNode = getGal().getFromNetworkCache(_newWrapperNode)) != null) {
									LOG.info("AutoDiscoveryUnknownNodes procedure of Node: {}",
											String.format("%04X", messageEvent.getSourceAddress().getNetworkAddress()));

									/*
									 * Reading the IEEEAddress of the new node
									 */
									int counter = 0;
									while ((_newWrapperNode.get_node().getAddress().getIeeeAddress() == null) && counter <= NUMBEROFRETRY) {
										try {
											LOG.debug("AUTODISCOVERY:Sending IeeeReq to: {}",
													String.format("%04X", _newWrapperNode.get_node().getAddress().getNetworkAddress()));
											BigInteger _iee = readExtAddress(INTERNAL_TIMEOUT,
													_newWrapperNode.get_node().getAddress().getNetworkAddress());
											synchronized (_newWrapperNode) {
												_newWrapperNode.get_node().getAddress().setIeeeAddress(_iee);
											}
											LOG.debug("Readed Ieee of the new node: {} Ieee: {}",
													String.format("%04X", _newWrapperNode.get_node().getAddress().getNetworkAddress()),
													_newWrapperNode.get_node().getAddress().getIeeeAddress().toString());

										} catch (Exception e) {
											LOG.error("Error reading Ieee of node: {}",
													String.format("%04X", _newWrapperNode.get_node().getAddress().getNetworkAddress()));
											counter++;

										}
									}
									if (counter > NUMBEROFRETRY) {
										getGal().getNetworkcache().remove(_newWrapperNode);
										return;
									}

									counter = 0;

									while (_newWrapperNode.getNodeDescriptor() == null && counter <= NUMBEROFRETRY) {
										try {
											LOG.debug("AUTODISCOVERY:Sending NodeDescriptorReq to: {}",
													String.format("%04X", _newWrapperNode.get_node().getAddress().getNetworkAddress()));

											NodeDescriptor _desc = getNodeDescriptorSync(INTERNAL_TIMEOUT, _newWrapperNode.get_node().getAddress());

											synchronized (_newWrapperNode) {
												_newWrapperNode.setNodeDescriptor(_desc);
												_newWrapperNode.get_node()
														.setCapabilityInformation(_newWrapperNode.getNodeDescriptor().getMACCapabilityFlag());
											}
											LOG.debug("Readed NodeDescriptor of the new node:"
													+ String.format("%04X", _newWrapperNode.get_node().getAddress().getNetworkAddress()));

										} catch (Exception e) {
											LOG.error("Error reading Node Descriptor of node:"
													+ String.format("%04X", _newWrapperNode.get_node().getAddress().getNetworkAddress()));
											counter++;

										}
									}

									if (counter > NUMBEROFRETRY) {
										getGal().getNetworkcache().remove(_newWrapperNode);
										return;
									}

									synchronized (_newWrapperNode) {
										_newWrapperNode.reset_numberOfAttempt();
									}

									if (!_newWrapperNode.isSleepyOrEndDevice()) {
										synchronized (_newWrapperNode) {
											_newWrapperNode.set_discoveryCompleted(false);
										}

										if (configuration.getKeepAliveThreshold() > 0) {
											_newWrapperNode.setTimerFreshness(configuration.getKeepAliveThreshold());
										}

										if (configuration.getForcePingTimeout() > 0) {
											/*
											 * Starting immediately a ForcePig in order to retrieve
											 * the LQI informations on the new node
											 */
											_newWrapperNode.setTimerForcePing(1);
										}
									} else {
										synchronized (_newWrapperNode) {
											_newWrapperNode.set_discoveryCompleted(true);
										}
										Status _st = new Status();
										_st.setCode((short) GatewayConstants.SUCCESS);

										LOG.debug("\n\rNodeDiscovered From AutodiscoveredNode Sleepy:"
												+ String.format("%04X", _newWrapperNode.get_node().getAddress().getNetworkAddress()) + "\n\r");

										try {
											getGal().get_gatewayEventManager().nodeDiscovered(_st, _newWrapperNode.get_node());
										} catch (Exception e) {
											LOG.error("Error Calling NodeDescovered from AutodiscoveredNode Sleepy:"
													+ String.format("%04X", _newWrapperNode.get_node().getAddress().getNetworkAddress()) + " Error:"
													+ e.getMessage());

										}
									}

									/*
									 * Saving the PANID in order to leave the Philips light
									 */
									getGal().getManageMapPanId().setPanid(_newWrapperNode.get_node().getAddress().getIeeeAddress(),
											getGal().getNetworkPanID());
								}

							}
						};

						Thread _thr0 = new Thread(thr);
						_thr0.setName("Thread getAutoDiscoveryUnknownNodes:" + String.format("%04X", address.getNetworkAddress()));
						_thr0.start();
						return null;
					}
				}
			}
		}

		return Wrapnode;
	}

	private Status makeStatusObject(String message, short code) {
		Status toReturn = new Status();
		toReturn.setMessage(message);
		toReturn.setCode(code);
		return toReturn;
	}

	public void SendRs232Data(final ByteArrayObject toAdd) throws Exception {
		// getIKeyInstance().write(toAdd);
		RS232Filter.getInstance().write(toAdd);
	}

	/**
	 * Add StartSequence + Control to passed BufferArrayObject
	 * 
	 * @param frame
	 * @param commandCode
	 * @return
	 */

	public ByteArrayObject Set_SequenceStart_And_FSC(ByteArrayObject frame, short commandCode) {

		byte size = (byte) frame.getCount(false);
		byte opgroup = (byte) ((commandCode >> 8) & 0xFF);
		byte opcode = (byte) (commandCode & 0xFF);
		frame.addOPGroup(opgroup);
		frame.addOPCode(opcode);
		frame.addLength(size);

		byte FSC = 0;

		for (Byte b : frame.getArray()) {
			FSC ^= b.byteValue();
		}

		frame.addStartSequance((byte) 0x02);
		frame.addByte(FSC);
		return frame;
	}

	// Set an APS information base (AIB) attribute.
	public Status APSME_SETSync(long timeout, short _AttID, String _value) throws GatewayException, Exception {

		short opcode = FreescaleConstants.APSMESetRequest;

		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addByte((byte) _AttID);/* _AttId */
		frame.addByte((byte) 0x00);
		frame.addByte((byte) 0x00);
		frame.addByte((byte) 0x00);
		for (byte x : DataManipulation.hexStringToByteArray(_value))
			frame.addByte(x);
		frame = Set_SequenceStart_And_FSC(frame, opcode);

		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.APSME_SET);
		getListLocker().add(lock);

		SendRs232Data(frame);

		return waitStatus(lock, opcode, timeout);
	}

	// Get APS information base (AIB) attributes.
	public String APSME_GETSync(long timeout, short _AttID) throws Exception {

		short opcode = FreescaleConstants.APSMEGetRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addByte((byte) _AttID);/* iId */
		frame.addByte((byte) 0x00);/* iIndex */
		frame.addByte((byte) 0x00);/* iEntries */
		frame.addByte((byte) 0x00);/* iEntrySize */

		frame = Set_SequenceStart_And_FSC(frame, opcode);

		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.APSME_GET, String.format("%02X", _AttID));
		getListLocker().add(lock);

		SendRs232Data(frame);

		return (String) waitResponse(lock, opcode, timeout);
	}

	// Get network information base Attributes.
	public String NMLE_GetSync(long timeout, short _AttID, short iEntry) throws Exception {
		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addByte((byte) _AttID);/* iId */
		frame.addByte((byte) 0x00);/* iIndex */
		frame.addByte((byte) iEntry);/* iEntries */
		frame.addByte((byte) 0x00);/* iEntrySize */
		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.NLMEGetRequest);
		LOG.debug("NLME-GET.Request: {}", frame.toString());

		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.NMLE_GET);
		lock.set_Key(String.format("%02X", _AttID));
		Status status = new Status();

		getListLocker().add(lock);

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in NLME-GET.Request");

			throw new GatewayException("Timeout expired in NLME-GET.Request");
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());

				throw new GatewayException(
						"Error on  NLME-GET.Request. Status code:" + status.getCode() + " Status Message: " + status.getMessage());

			} else
				return (String) lock.get_objectOfResponse();
		}

	}

	public Status stopNetworkSync(long timeout) throws Exception, GatewayException {
		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addByte((byte) 0x01);/*
																 * Stop Mode AnnounceStop (Stops after
																 * announcing it is leaving the network.)
																 */
		frame.addByte((byte) 0x00);/*
																 * Reset binding/group tables, node type, PAN ID
																 * etc to ROM state.
																 */
		frame.addByte((byte) 0x00);/* Restart after stopping. */
		frame.addByte((byte) 0xFF);/* Writes NVM upon stop. */

		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.ZTCStopNwkExRequest);

		LOG.debug("ZDP-StopNwkEx.Request: {}", frame.toString());

		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.STOP_NETWORK);
		Status status = new Status();

		getListLocker().add(lock);

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in ZDP-StopNwkEx.Request");

			throw new GatewayException("Timeout expired in ZDP-StopNwkEx.Request");
		} else {
			if (status.getCode() != 0) {

				LOG.info("Returned Status: ", status.getCode());

				throw new GatewayException(
						"Error on  ZDP-StopNwkEx.Request. Status code:" + status.getCode() + " Status Message: " + status.getMessage());
			}
			return status;
		}

	}

	// Send a message to Freescale.
	public Status sendApsSync(long timeout, APSMessage message) throws Exception {

		LOG.debug("Data_FreeScale.send_aps");
		// ParserLocker lock = new ParserLocker();
		// lock.setType(TypeMessage.APS);
		/* DestAddress + DestEndPoint + SourceEndPoint */
		BigInteger _DSTAdd = null;
		if ((message.getDestinationAddressMode() == GatewayConstants.EXTENDED_ADDRESS_MODE)) {
			// _Bruno_
			// _DSTAdd = message.getDestinationAddress().getIeeeAddress();
			_DSTAdd = new BigInteger(1, Utils.longToByteArray(message.getDestinationAddress().getIeeeAddress().longValue()));
		} else if ((message.getDestinationAddressMode() == GatewayConstants.ADDRESS_MODE_SHORT))
			_DSTAdd = BigInteger.valueOf(message.getDestinationAddress().getNetworkAddress());
		else if (((message.getDestinationAddressMode() == GatewayConstants.ADDRESS_MODE_ALIAS)))
			throw new Exception("The DestinationAddressMode == ADDRESS_MODE_ALIAS is not implemented!!");
		if (_DSTAdd != null) {

			LOG.debug("Data_FreeScale.send_aps - Destination Address: " + _DSTAdd.toString(16));

			// String _key = String.format("%016X", _DSTAdd.longValue()) +
			// String.format("%02X", message.getDestinationEndpoint()) +
			// String.format("%02X", message.getSourceEndpoint());
			// lock.set_Key(_key);
			Status status = new Status();
			// getListLocker().add(lock);
			SendRs232Data(makeByteArrayFromApsMessage(message));

			status.setCode((short) GatewayConstants.SUCCESS);
			return status;

		} else {
			throw new GatewayException("Error on APSDE-DATA.Request.Request. Destination address is null");

		}
	}

	public short configureEndPointSync(long timeout, SimpleDescriptor desc) throws IOException, Exception, GatewayException {

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addByte(desc.getEndPoint().byteValue());/* End Point */
		frame.addBytesShort(Short.reverseBytes(desc.getApplicationProfileIdentifier().shortValue()), 2);
		frame.addBytesShort(Short.reverseBytes(desc.getApplicationDeviceIdentifier().shortValue()), 2);
		frame.addByte(
				desc.getApplicationDeviceVersion().byteValue());/* DeviceVersion */
		frame.addByte(
				(byte) desc.getApplicationInputCluster().size());/* ClusterInputSize */
		if (desc.getApplicationInputCluster().size() > 0) {
			for (Integer x : desc.getApplicationInputCluster())
				frame.addBytesShort(Short.reverseBytes(x.shortValue()), 2);
		}

		frame.addByte((byte) desc.getApplicationOutputCluster()
				.size());/* ClusterOutputSize */
		if (desc.getApplicationOutputCluster().size() > 0) {
			for (Integer x : desc.getApplicationOutputCluster())
				frame.addBytesShort(Short.reverseBytes(x.shortValue()), 2);
		}
		frame.addByte((byte) 0x01);/* Maximum Window Size */

		frame = Set_SequenceStart_And_FSC(frame,
				FreescaleConstants.APSRegisterEndPointRequest);/*
																												 * StartSequence +
																												 * Control
																												 */
		/* APS-RegisterEndPoint.Request */
		LOG.debug("Configure EndPoint command: {}", frame.toString());
		short _endPoint = 0;

		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.CONFIGURE_END_POINT);
		Status status = new Status();

		getListLocker().add(lock);

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in Configure End Point");

			throw new GatewayException("Timeout expired in Configure End Point");
		} else {
			if (status.getCode() != 0) {
				LOG.info("Returned Status: {}", status.getCode());
				throw new GatewayException(
						"Error on  APS-RegisterEndPoint.Request. Status code:" + status.getCode() + " Status Message: " + status.getMessage());
			} else
				_endPoint = desc.getEndPoint();
		}
		return _endPoint;
	}

	public ByteArrayObject makeByteArrayFromApsMessage(APSMessage apsMessage) throws Exception {
		byte[] data = apsMessage.getData();

		ByteArrayObject frame = new ByteArrayObject(false);
		byte dam = apsMessage.getDestinationAddressMode().byteValue();
		frame.addByte(dam);
		Address address = apsMessage.getDestinationAddress();

		byte[] _reversed = null;
		switch (dam) {
		case GatewayConstants.ADDRESS_MODE_SHORT:
			byte[] networkAddress = DataManipulation.toByteVect(address.getNetworkAddress(), 8);
			_reversed = DataManipulation.reverseBytes(networkAddress);
			for (byte b : _reversed)
				frame.addByte(b);
			break;
		case GatewayConstants.EXTENDED_ADDRESS_MODE:
			byte[] ieeeAddress = DataManipulation.toByteVect(address.getIeeeAddress(), 8);
			_reversed = DataManipulation.reverseBytes(ieeeAddress);
			for (byte b : _reversed)
				frame.addByte(b);
			break;
		case GatewayConstants.ADDRESS_MODE_ALIAS:
			throw new UnsupportedOperationException("Address Mode Alias");
		default:
			throw new Exception("Address Mode undefined!");

		}

		frame.addByte((byte) apsMessage.getDestinationEndpoint());

		frame.addBytesShort(Short.reverseBytes(apsMessage.getProfileID().shortValue()), 2);

		frame.addBytesShort(Short.reverseBytes((short) apsMessage.getClusterID()), 2);

		frame.addByte((byte) apsMessage.getSourceEndpoint());

		if (data.length > 0x64) {
			throw new Exception("ASDU length must 0x64 or less in length");
		} else {
			frame.addByte((byte) data.length);

		}

		for (Byte b : data)
			frame.addByte(b);

		TxOptions txo = apsMessage.getTxOptions();
		int bitmap = 0x00;
		if (txo.isSecurityEnabled()) {
			bitmap |= 0x01;
		}
		if (txo.isUseNetworkKey()) {
			bitmap |= 0x02;
		}
		if (txo.isAcknowledged()) {
			bitmap |= 0x04;
		}
		if (txo.isPermitFragmentation()) {
			bitmap |= 0x08;
		}
		frame.addByte((byte) bitmap);

		frame.addByte((byte) apsMessage.getRadius());

		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.APSDEDataRequest);
		LOG.debug("Write APS on: {} Message: {}", System.currentTimeMillis(), frame.toString());

		return frame;
	}

	public ByteArrayObject makeByteArrayFromInterPANMessage(InterPANMessage message) throws Exception {
		ByteArrayObject frame = new ByteArrayObject(false);
		byte sam = (byte) message.getSrcAddressMode();
		frame.addByte(sam);

		byte dam = (byte) message.getDstAddressMode();
		frame.addByte(dam);

		frame.addBytesShort(Short.reverseBytes((short) message.getDestPANID()), 2);

		Address dstaddress = message.getDestinationAddress();
		byte[] _reversed = null;

		switch (dam) {

		case GatewayConstants.ADDRESS_MODE_SHORT:
			byte[] networkAddress = DataManipulation.toByteVect(dstaddress.getNetworkAddress(), 8);
			_reversed = DataManipulation.reverseBytes(networkAddress);
			for (byte b : _reversed)
				frame.addByte(b);
			break;

		case GatewayConstants.EXTENDED_ADDRESS_MODE:
			byte[] ieeeAddress = DataManipulation.toByteVect(dstaddress.getIeeeAddress(), 8);
			_reversed = DataManipulation.reverseBytes(ieeeAddress);
			for (byte b : _reversed)
				frame.addByte(b);
			break;

		case GatewayConstants.ADDRESS_MODE_ALIAS:
			// TODO Control those address modes!
			throw new UnsupportedOperationException("Address Mode Alias");

		default:
			throw new Exception("Address Mode undefined!");
		}

		frame.addBytesShort(Short.reverseBytes(message.getProfileID().shortValue()), 2);

		frame.addBytesShort(Short.reverseBytes((short) message.getClusterID()), 2);

		if (message.getASDULength() > 0x64) {
			throw new Exception("ASDU length must 0x64 or less in length");
		} else {
			frame.addByte((byte) message.getASDULength());

		}

		for (Byte b : message.getASDU())
			frame.addByte(b);

		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.InterPANDataRequest);
		LOG.debug("Write InterPanMessage on: {} Message: {}", System.currentTimeMillis(), frame.toString());

		return frame;
	}

	public Status SetModeSelectSync(long timeout) throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZTCModeSelectRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addByte((byte) 0x01);/* UART Tx Blocking */
		frame.addByte((byte) 0x02);/* MCPS */
		frame.addByte((byte) 0x02);/* MLME */
		frame.addByte((byte) 0x02);/* ASP */
		frame.addByte((byte) 0x02);/* NLDE */
		frame.addByte((byte) 0x02);/* NLME */
		frame.addByte((byte) 0x02);/* APSDE */
		frame.addByte((byte) 0x02);/* AFDE */
		frame.addByte((byte) 0x02);/* APSME */
		frame.addByte((byte) 0x02);/* ZDP */
		frame.addByte((byte) 0x00);/* HealthCare */

		frame = Set_SequenceStart_And_FSC(frame, opcode);

		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.MODE_SELECT);
		getListLocker().add(lock);

		SendRs232Data(frame);

		return waitStatus(lock, opcode, timeout);
	}

	public Status startGatewayDeviceSync(long timeout, StartupAttributeInfo sai) throws IOException, Exception, GatewayException {
		Status _statWriteSas = WriteSasSync(timeout, sai);
		if (_statWriteSas.getCode() == 0) {

			LOG.info("Starting Network...");

			LogicalType devType = getGal().getPropertiesManager().getSturtupAttributeInfo().getDeviceType();
			ByteArrayObject frame = new ByteArrayObject(false);

			if (devType == LogicalType.CURRENT) {
				throw new Exception("LogicalType not Valid!");
			} else if (devType == LogicalType.COORDINATOR) {

				frame.addByte(
						FreescaleConstants.DeviceType.Coordinator);/* Coordinator */
				LOG.debug("DeviceType == COORDINATOR");

			} else if (devType == LogicalType.END_DEVICE) {
				frame.addByte(FreescaleConstants.DeviceType.EndDevice);/*
																																 * End Device
																																 */
				LOG.debug("DeviceType == ENDDEVICE");

			} else if (devType == LogicalType.ROUTER) {
				frame.addByte(FreescaleConstants.DeviceType.Router);/* Router */
				LOG.debug("DeviceType == ROUTER");

			}
			LOG.debug("StartupSet value read from PropertiesManager: {}", getGal().getPropertiesManager().getStartupSet());
			LOG.debug("StartupControlMode value read from PropertiesManager: {}",
					getGal().getPropertiesManager().getSturtupAttributeInfo().getStartupControl().byteValue());

			frame.addByte((byte) getGal().getPropertiesManager().getStartupSet());
			frame.addByte(getGal().getPropertiesManager().getSturtupAttributeInfo().getStartupControl().byteValue());

			frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.ZTCStartNwkExRequest);
			LOG.debug("Start Network command: {} ---Timeout: {}", frame.toString(), timeout);

			ParserLocker lock = new ParserLocker();
			lock.setType(TypeMessage.START_NETWORK);
			Status status = new Status();

			getListLocker().add(lock);
			SendRs232Data(frame);
			if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
				lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
			status = lock.getStatus();

			if (getListLocker().contains(lock))
				getListLocker().remove(lock);

			if (status.getCode() == ParserLocker.INVALID_ID) {

				LOG.error("Timeout expired in startGatewayDevice");

				throw new GatewayException("Timeout expired in ZDP-StartNwkEx.Request");
			} else {
				if (status.getCode() != 0) {

					LOG.debug("Returned Status: {}", status);

					throw new GatewayException(
							"Error on ZDP-StartNwkEx.Request. Status code:" + status.getCode() + " Status Message: " + status.getMessage());
				}
			}

			return status;
		} else
			return _statWriteSas;
	}

	private Status WriteSasSync(long timeout, StartupAttributeInfo sai) throws InterruptedException, Exception {
		if (sai.getChannelMask() == null)
			sai = getGal().getPropertiesManager().getSturtupAttributeInfo();

		LogicalType devType = sai.getDeviceType();

		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addBytesShort(Short.reverseBytes(sai.getShortAddress().shortValue()), 2);

		/* Extended PanID */
		byte[] ExtendedPaniId = DataManipulation.toByteVect(sai.getExtendedPANId(), 8);
		LOG.debug("Extended PanID: {}", DataManipulation.convertBytesToString(ExtendedPaniId));

		for (byte b : DataManipulation.reverseBytes(ExtendedPaniId))
			frame.addByte(b);

		/* Extended APS Use Extended PAN Id */
		byte[] APSUseExtendedPANId = DataManipulation.toByteVect(BigInteger.ZERO, 8);
		LOG.info("APS Use Extended PAN Id: {}", DataManipulation.convertBytesToString(APSUseExtendedPANId));

		for (byte b : DataManipulation.reverseBytes(APSUseExtendedPANId))
			frame.addByte(b);
		frame.addBytesShort(Short.reverseBytes(sai.getPANId().shortValue()), 2);
		byte[] _channel = Utils.buildChannelMask(sai.getChannelMask().shortValue());

		LOG.debug("Channel readed from PropertiesManager: {}", sai.getChannelMask());

		LOG.debug("Channel after conversion: {}", DataManipulation.convertArrayBytesToString(_channel));

		for (byte x : DataManipulation.reverseBytes(_channel))
			frame.addByte(x);

		frame.addByte(sai.getProtocolVersion().byteValue());
		frame.addByte(sai.getStackProfile().byteValue());
		frame.addByte(sai.getStartupControl().byteValue());

		/* TrustCenterAddress */
		byte[] TrustCenterAddress = DataManipulation.toByteVect(sai.getTrustCenterAddress(), 8);
		LOG.debug("TrustCenterAddress:" + DataManipulation.convertBytesToString(TrustCenterAddress));

		for (byte b : DataManipulation.reverseBytes(TrustCenterAddress))
			frame.addByte(b);

		/* TrustCenterMasterKey */
		byte[] TrustCenterMasterKey = (devType == LogicalType.COORDINATOR) ? sai.getTrustCenterMasterKey()
				: DataManipulation.toByteVect(BigInteger.ZERO, 16);
		LOG.debug("TrustCenterMasterKey: {}", DataManipulation.convertBytesToString(TrustCenterMasterKey));

		for (byte b : DataManipulation.reverseBytes(TrustCenterMasterKey))
			frame.addByte(b);

		/* NetworKey */
		byte[] NetworKey = (devType == LogicalType.COORDINATOR) ? sai.getNetworkKey()
				: DataManipulation.toByteVect(BigInteger.ZERO, 16);
		LOG.debug("NetworKey: {}", DataManipulation.convertBytesToString(NetworKey));

		for (byte b : DataManipulation.reverseBytes(NetworKey))
			frame.addByte(b);

		frame.addByte((sai.isUseInsecureJoin()) ? ((byte) 0x01) : ((byte) 0x00));

		/* PreconfiguredLinkKey */
		byte[] PreconfiguredLinkKey = sai.getPreconfiguredLinkKey();
		LOG.debug("PreconfiguredLinkKey: {}", DataManipulation.convertBytesToString(PreconfiguredLinkKey));

		for (byte b : PreconfiguredLinkKey)
			frame.addByte(b);

		frame.addByte(sai.getNetworkKeySeqNum().byteValue());
		frame.addByte((byte) 0x01);

		frame.addBytesShort(Short.reverseBytes(sai.getNetworkManagerAddress().shortValue()), 2);
		frame.addByte(sai.getScanAttempts().byteValue());

		frame.addBytesShort(sai.getTimeBetweenScans().shortValue(), 2);

		frame.addBytesShort(Short.reverseBytes(sai.getRejoinInterval().shortValue()), 2);

		frame.addBytesShort(Short.reverseBytes(sai.getMaxRejoinInterval().shortValue()), 2);

		frame.addBytesShort(Short.reverseBytes(sai.getIndirectPollRate().shortValue()), 2);

		frame.addByte(sai.getParentRetryThreshold().byteValue());

		frame.addByte((sai.isConcentratorFlag()) ? ((byte) 0x01) : ((byte) 0x00));

		frame.addByte(sai.getConcentratorRadius().byteValue());

		frame.addByte(sai.getConcentratorDiscoveryTime().byteValue());

		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.BlackBoxWriteSAS);

		LOG.debug("WriteSas Command: {} --Timeout: {}", frame.toString(), timeout);

		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.WRITE_SAS);
		Status status = new Status();
		getListLocker().add(lock);
		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID) {

			LOG.debug("Waiting WriteSas confirm...");
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
			LOG.debug("Ended waiting WriteSas confirm!");

		}
		status = lock.getStatus();
		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {
			LOG.error("Timeout expired in write sas");
			throw new GatewayException("Timeout expired in write sas");
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());
				throw new GatewayException(
						"Error on BlackBox.WriteSAS. Status code:" + status.getCode() + " Status Message: " + status.getMessage());
			}
			return status;
		}
	}

	public Status permitJoinSync(long timeout, Address addrOfInterest, short duration, byte TCSignificance)
			throws IOException, Exception, GatewayException {

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addBytesShort(Short.reverseBytes(addrOfInterest.getNetworkAddress().shortValue()),
				2);/*
						 * Short Network Address
						 */
		frame.addByte((byte) duration);/* Duration */
		frame.addByte(TCSignificance);/* TCSignificant */
		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.NLMEPermitJoiningRequest);
		LOG.debug("Permit Join command: {}", frame.toString());

		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.PERMIT_JOIN);
		Status status = new Status();

		getListLocker().add(lock);

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);

		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID)
			throw new GatewayException("Timeout expired in Permit Join");
		else {
			if (status.getCode() != 0)
				throw new GatewayException(
						"Error on ZDP-Mgmt_Permit_Join.Request. Status code:" + status.getCode() + " Status Message: " + status.getMessage());

		}
		return status;
	}

	public Status permitJoinAllSync(long timeout, Address addrOfInterest, short duration, byte TCSignificance)
			throws IOException, Exception {

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addBytesShort(Short.reverseBytes(addrOfInterest.getNetworkAddress().shortValue()),
				2);/*
						 * Short Network Address
						 */
		frame.addByte((byte) duration);/* Duration */
		frame.addByte(TCSignificance);/* TCSignificant */
		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.NLMEPermitJoiningRequest);
		LOG.debug("Permit Join command: {}", frame.toString());

		SendRs232Data(frame);
		Status status = new Status();
		status.setCode((short) GatewayConstants.SUCCESS);

		return status;
	}

	public short getChannelSync(long timeout) throws IOException, Exception, GatewayException {
		ByteArrayObject frame = new ByteArrayObject(false);
		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.ZTCGetChannelRequest);// StartSequence
		// +
		// Control
		LOG.debug("ZTC-GetChannel.Request: {}", frame.toString());

		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.CHANNEL_REQUEST);
		Status status = new Status();

		getListLocker().add(lock);

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in ZTC-GetChannel.Request");

			throw new GatewayException("Timeout expired in ZTC-GetChannel.Request");
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());
				throw new GatewayException(
						"Error on ZTC-GetChannel.Request. Status code:" + status.getCode() + " Status Message: " + status.getMessage());
			} else
				return ((Short) lock.get_objectOfResponse()).shortValue();
		}
	}

	public BigInteger readExtAddressGal(long timeout) throws GatewayException, Exception {

		ByteArrayObject frame = new ByteArrayObject(false);

		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.ZTCReadExtAddrRequest);// StartSequence
		// +
		// Control

		LOG.debug("ZTC-ReadExtAddr.Request: {}", frame.toString());

		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.READ_EXT_ADDRESS);
		Status status = new Status();

		getListLocker().add(lock);

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in ZTC-ReadExtAddr.Request");

			throw new GatewayException("Timeout expired in ZTC-ReadExtAddr.Request");
		} else {
			if (status.getCode() != 0) {

				LOG.debug("Returned Status: {}", status.getCode());
				throw new GatewayException(
						"Error on ZTC-ReadExtAddr.Request. Status code:" + status.getCode() + " Status Message: " + status.getMessage());
			} else
				return ((BigInteger) lock.get_objectOfResponse());
		}
	}

	public BigInteger readExtAddress(long timeout, Integer shortAddress) throws GatewayException, Exception {

		short opcode = FreescaleConstants.ZDPIeeeAddrRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addBytesShort(Short.reverseBytes(shortAddress.shortValue()), 2);
		frame.addBytesShort(Short.reverseBytes(shortAddress.shortValue()), 2);

		frame.addByte((byte) 0x01);/* Request Type */
		frame.addByte((byte) 0x00);/* StartIndex */

		frame = Set_SequenceStart_And_FSC(frame, opcode);

		LOG.info(frame.toString());

		String key = String.format("%04X", shortAddress);

		ParserLocker lock = new ParserLocker(TypeMessage.READ_IEEE_ADDRESS, key);
		getListLocker().add(lock);

		SendRs232Data(frame);

		return (BigInteger) waitResponse(lock, opcode, timeout);
	}

	public NodeDescriptor getNodeDescriptorSync(long timeout, Address addrOfInterest)
			throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZDPNodeDescriptorRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addBytesShort(Short.reverseBytes(addrOfInterest.getNetworkAddress().shortValue()), 2);
		frame.addBytesShort(Short.reverseBytes(addrOfInterest.getNetworkAddress().shortValue()), 2);
		frame = Set_SequenceStart_And_FSC(frame, opcode);

		String key = String.format("%04X", addrOfInterest.getNetworkAddress());

		ParserLocker lock = new ParserLocker(TypeMessage.NODE_DESCRIPTOR, key);
		getListLocker().add(lock);

		SendRs232Data(frame);

		return (NodeDescriptor) waitResponse(lock, opcode, timeout);
	}

	public List<Short> startServiceDiscoverySync(long timeout, Address aoi) throws Exception {

		short opcode = FreescaleConstants.ZDPActiveEpRequest;

		LOG.debug("startServiceDiscoverySync Timeout: {}", timeout);

		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addBytesShort(Short.reverseBytes(aoi.getNetworkAddress().shortValue()), 2);
		frame.addBytesShort(Short.reverseBytes(aoi.getNetworkAddress().shortValue()), 2);

		frame = Set_SequenceStart_And_FSC(frame, opcode);

		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.ACTIVE_EP, String.format("%04X", aoi.getNetworkAddress()));
		getListLocker().add(lock);

		SendRs232Data(frame);

		return (List<Short>) waitStatus(lock, opcode, timeout);
	}

	public Status leaveSync(long timeout, Address addrOfInterest, int mask) throws Exception {
		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addBytesShort(Short.reverseBytes(addrOfInterest.getNetworkAddress().shortValue()), 2);
		byte[] deviceAddress = DataManipulation.toByteVect(getGal().get_GalNode().get_node().getAddress().getNetworkAddress(), 8);
		byte[] _reversed = DataManipulation.reverseBytes(deviceAddress);

		for (byte b : _reversed) {
			frame.addByte(b);
		}

		byte options = 0;
		options = (byte) (options & GatewayConstants.LEAVE_REJOIN);
		options = (byte) (options & GatewayConstants.LEAVE_REMOVE_CHILDERN);
		frame.addByte(options);
		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.ZDPMgmtLeaveRequest);
		LOG.debug("Leave command: {}", frame.toString());

		SendRs232Data(frame);

		/* In order to skip error during these commands */
		try {
			Status _st0 = ClearDeviceKeyPairSet(timeout, addrOfInterest);
		} catch (Exception e) {
		}

		try {
			Status _st1 = ClearNeighborTableEntry(timeout, addrOfInterest);
		} catch (Exception e) {
		}

		/* Always success to up layers */
		Status status = new Status();
		status.setCode((short) GatewayConstants.SUCCESS);

		return status;
	}

	public Status clearEndpointSync(long timeout, short endpoint) throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.APSDeRegisterEndPointRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addByte((byte) endpoint);/* EndPoint */
		frame = Set_SequenceStart_And_FSC(frame, opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.DEREGISTER_END_POINT);
		getListLocker().add(lock);

		SendRs232Data(frame);

		return waitStatus(lock, opcode, timeout);
	}

	public NodeServices getLocalServices(long timeout) throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.APSGetEndPointIdListRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame = Set_SequenceStart_And_FSC(frame, opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.GET_END_POINT_LIST);
		getListLocker().add(lock);

		LOG.debug(frame.toString());

		SendRs232Data(frame);

		return (NodeServices) waitResponse(lock, opcode, timeout);
	}

	public ServiceDescriptor getServiceDescriptor(long timeout, Address addrOfInterest, short endpoint)
			throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZDPSimpleDescriptorRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addBytesShort(Short.reverseBytes(addrOfInterest.getNetworkAddress().shortValue()), 2);
		frame.addBytesShort(Short.reverseBytes(addrOfInterest.getNetworkAddress().shortValue()), 2);
		frame.addByte((byte) endpoint);

		frame = Set_SequenceStart_And_FSC(frame, opcode);

		String key = String.format("%04X", addrOfInterest.getNetworkAddress()) + String.format("%02X", endpoint);

		LOG.debug("ZDP-SimpleDescriptor.Request command: {}", frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.GET_SIMPLE_DESCRIPTOR, key);
		getListLocker().add(lock);

		SendRs232Data(frame);

		return (ServiceDescriptor) waitResponse(lock, opcode, timeout);
	}

	public void clearBuffer() {
		try {
			getDataFromSerialComm().put(new ByteArrayObject(null, 0));
		} catch (InterruptedException e) {
		}
		getDataFromSerialComm().clear();
	}

	public void cpuReset() throws Exception {
		ByteArrayObject frame = new ByteArrayObject(false);

		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.ZTCCPUResetRequest);
		LOG.debug("CPUResetCommnad command: {}", frame.toString());
		SendRs232Data(frame);
	}

	public BindingList getNodeBindings(long timeout, Address addrOfInterest, short index)
			throws IOException, Exception, GatewayException {
		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addBytesShort(Short.reverseBytes(addrOfInterest.getNetworkAddress().shortValue()), 2);
		frame.addByte((byte) index);
		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.ZDPMgmtBindRequest);
		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.GET_BINDINGS);
		Status status = new Status();

		getListLocker().add(lock);

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in ZDP-Mgmt_Bind.Request");

			throw new GatewayException("Timeout expired in ZDP-Mgmt_Bind.Request");
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());

				throw new GatewayException(
						"Error on ZDP-Mgmt_Bind.Request. Status code:" + status.getCode() + " Status Message: " + status.getMessage());
			} else {
				BindingList _tores = (BindingList) lock.get_objectOfResponse();
				return _tores;
			}
		}
	}

	public Status addBinding(long timeout, Binding binding, Address aoi) throws IOException, Exception, GatewayException {
		byte[] _reversed;

		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addBytesShort(Short.reverseBytes(aoi.getNetworkAddress().shortValue()), 2);

		byte[] ieeeAddress = DataManipulation.toByteVect(binding.getSourceIEEEAddress(), 8);
		_reversed = DataManipulation.reverseBytes(ieeeAddress);
		for (byte b : _reversed) {
			/* Source IEEEAddress */
			frame.addByte(b);
		}

		frame.addByte((byte) binding.getSourceEndpoint());

		Integer _clusterID = binding.getClusterID();

		/* ClusterID */
		frame.addBytesShort(Short.reverseBytes(_clusterID.shortValue()), 2);

		if (binding.getDeviceDestination().size() > 0 && binding.getGroupDestination().size() > 0)
			throw new GatewayException("The Address mode can only be one between Group or Device!");
		else if (binding.getDeviceDestination().size() == 1) {
			/*
			 * Destination AddressMode IeeeAddress + EndPoint
			 */
			frame.addByte((byte) 0x03);

			byte[] _DestinationieeeAddress = DataManipulation.toByteVect(binding.getDeviceDestination().get(0).getAddress(), 8);
			_reversed = DataManipulation.reverseBytes(_DestinationieeeAddress);
			for (byte b : _reversed)
				/* Destination IEEEAddress */
				frame.addByte(b);

			/*
			 * Destination EndPoint
			 */
			frame.addByte((byte) binding.getDeviceDestination().get(0).getEndpoint());
		} else if (binding.getGroupDestination().size() == 1) {
			frame.addByte((byte) 0x01);/* Destination AddressMode Group */

			byte[] _DestinationGroupAddress = DataManipulation.toByteVect(binding.getGroupDestination().get(0).longValue(), 8);
			_reversed = DataManipulation.reverseBytes(_DestinationGroupAddress);
			for (byte b : _reversed)
				/* Destination Group */
				frame.addByte(b);

		} else {
			throw new GatewayException("The Address mode can only be one Group or one Device!");

		}

		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.ZDPBindRequest);
		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.ADD_BINDING);
		Status status = new Status();

		getListLocker().add(lock);
		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in ZDP-BIND.Response");

			throw new GatewayException("Timeout expired in ZDP-BIND.Request");
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());

				throw new GatewayException(
						"Error on ZDP-BIND.Request. Status code:" + status.getCode() + " Status Message: " + status.getMessage());
			} else {
				return lock.getStatus();

			}
		}
	}

	public Status removeBinding(long timeout, Binding binding, Address aoi) throws IOException, Exception, GatewayException {
		byte[] _reversed;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addBytesShort(Short.reverseBytes(aoi.getNetworkAddress().shortValue()), 2);

		byte[] ieeeAddress = DataManipulation.toByteVect(binding.getSourceIEEEAddress(), 8);
		_reversed = DataManipulation.reverseBytes(ieeeAddress);
		for (byte b : _reversed)
			/* Source IEEEAddress */
			frame.addByte(b);

		frame.addByte((byte) binding.getSourceEndpoint());/* Source EndPoint */

		/* ClusterID */
		Integer _clusterID = binding.getClusterID();
		frame.addBytesShort(Short.reverseBytes(_clusterID.shortValue()), 2);

		if (binding.getDeviceDestination().size() > 0 && binding.getGroupDestination().size() > 0)
			throw new GatewayException("The Address mode can only be one between Group or Device!");
		else if (binding.getDeviceDestination().size() == 1) {
			frame.addByte(
					(byte) 0x03);/*
												 * Destination AddressMode IeeeAddress + EndPoint
												 */

			byte[] _DestinationieeeAddress = DataManipulation.toByteVect(binding.getDeviceDestination().get(0).getAddress(), 8);
			_reversed = DataManipulation.reverseBytes(_DestinationieeeAddress);
			for (byte b : _reversed)
				/* Destination IEEEAddress */
				frame.addByte(b);
			frame.addByte((byte) binding.getDeviceDestination().get(0)
					.getEndpoint());/*
													 * Destination EndPoint
													 */

		} else if (binding.getGroupDestination().size() == 1) {
			frame.addByte((byte) 0x01);/* Destination AddressMode Group */

			byte[] _DestinationGroupAddress = DataManipulation.toByteVect(binding.getGroupDestination().get(0).longValue(), 8);
			_reversed = DataManipulation.reverseBytes(_DestinationGroupAddress);
			for (byte b : _reversed)
				/* Destination Group */
				frame.addByte(b);

		} else {
			throw new GatewayException("The Address mode can only be one Group or one Device!");

		}

		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.ZDPUnbindRequest);
		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.REMOVE_BINDING);
		Status status = new Status();

		getListLocker().add(lock);

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in ZDP-UNBIND.Response");

			throw new GatewayException("Timeout expired in ZDP-UNBIND.Request");
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());

				throw new GatewayException(
						"Error on ZDP-UNBIND.Request. Status code:" + status.getCode() + " Status Message: " + status.getMessage());
			} else {
				return lock.getStatus();

			}
		}
	}

	public Status frequencyAgilitySync(long timeout, short scanChannel, short scanDuration)
			throws IOException, Exception, GatewayException {
		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addByte((byte) 0xFD);
		frame.addByte((byte) 0xFF);
		byte[] _channel = Utils.buildChannelMask(scanChannel);
		for (byte x : DataManipulation.reverseBytes(_channel))
			frame.addByte(x);
		frame.addByte((byte) scanDuration);
		frame.addByte((byte) 0x00);// Add parameter nwkupdate
		// _bodyCommand.addByte((byte) 0x01);// Add parameter nwkupdate
		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.NLMENWKUpdateReq);

		SendRs232Data(frame);
		Status _st = new Status();
		_st.setCode((short) GatewayConstants.SUCCESS);

		return _st;
	}

	public IConnector getIKeyInstance() {
		return dongleRs232;
	}

	public PropertiesManager getPropertiesManager() {
		return getGal().getPropertiesManager();
	}

	public void notifyFrame(final ByteArrayObject frame) {
		if (getGal().getPropertiesManager().getserialDataDebugEnabled())
			LOG.debug("<<< Received data:" + frame.toString());
		try {
			getDataFromSerialComm().put(frame);
		} catch (InterruptedException e) {
			LOG.error("Error on getTmpDataQueue().put:" + e.getMessage());
		}
	}

	public Status ClearDeviceKeyPairSet(long timeout, Address addrOfInterest) throws IOException, Exception, GatewayException {
		ByteArrayObject frame = new ByteArrayObject(false);
		byte[] ieeeAddress = DataManipulation.toByteVect(addrOfInterest.getIeeeAddress(), 8);
		byte[] _reversed = DataManipulation.reverseBytes(ieeeAddress);
		for (byte b : _reversed)
			frame.addByte(b);
		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.NLMEClearDeviceKeyPairSet);
		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.CLEAR_DEVICE_KEY_PAIR_SET);
		Status status = new Status();

		getListLocker().add(lock);

		LOG.debug("APS-ClearDeviceKeyPairSet.Request command: {}", frame.toString());

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in ClearDeviceKeyPairSet");

			throw new GatewayException("Timeout expired in ClearDeviceKeyPairSet");
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());

				throw new GatewayException("Error on APS-ClearDeviceKeyPairSet.Request. Status code:" + status.getCode()
						+ " Status Message: " + status.getMessage());
			} else {
				return status;
			}
		}

	}

	public Status ClearNeighborTableEntry(long timeout, Address addrOfInterest) throws IOException, Exception, GatewayException {
		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addByte((byte) 0xFF);
		frame.addByte((byte) 0xFF);

		byte[] ieeeAddress = DataManipulation.toByteVect(addrOfInterest.getIeeeAddress(), 8);
		byte[] _reversed = DataManipulation.reverseBytes(ieeeAddress);
		for (byte b : _reversed)
			frame.addByte(b);
		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.NLMEClearNeighborTableEntry);
		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.CLEAR_NEIGHBOR_TABLE_ENTRY);
		Status status = new Status();

		getListLocker().add(lock);

		LOG.debug("ZTC-ClearNeighborTableEntry.Request command: {}", frame.toString());

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in ZTC-ClearNeighborTableEntry.Request");

			throw new GatewayException("Timeout expired in ZTC-ClearNeighborTableEntry.Request");
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());

				throw new GatewayException("Error on ZTC-ClearNeighborTableEntry.Request. Status code:" + status.getCode()
						+ " Status Message: " + status.getMessage());
			} else {
				return status;
			}
		}
	}

	public Status NMLE_SETSync(long timeout, short _AttID, String _value) throws Exception {
		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addByte((byte) _AttID);/* _AttId */
		frame.addByte((byte) 0x00);
		frame.addByte((byte) 0x00);
		frame.addByte((byte) 0x00);
		for (byte x : DataManipulation.hexStringToByteArray(_value))
			frame.addByte(x);
		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.NLMESetRequest);
		LOG.debug("NMLE_SET command: {}", frame.toString());

		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.NMLE_SET);
		Status status = new Status();

		getListLocker().add(lock);

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);

		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in NMLE SET");

			throw new GatewayException("Timeout expired in NMLE SET");
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());

				throw new GatewayException(
						"Error on NMLE_SET.request. Status code:" + status.getCode() + " Status Message: " + status.getMessage());
			}
			return status;
		}
	}

	public Mgmt_LQI_rsp Mgmt_Lqi_Request(long timeout, Address addrOfInterest, short startIndex)
			throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZDPMgmtLqiRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addBytesShort(Short.reverseBytes(addrOfInterest.getNetworkAddress().shortValue()), 2);
		frame.addByte((byte) startIndex);

		frame = Set_SequenceStart_And_FSC(frame, opcode);

		LOG.debug(frame.toString());

		/*
		 * In case of a device different from the coordinator, we increase the
		 * timeout ...
		 */
		if (addrOfInterest.getNetworkAddress().shortValue() != 0) {
			timeout *= 10;
		}

		String key = String.format("%04X%02X", addrOfInterest.getNetworkAddress(), startIndex);

		ParserLocker lock = new ParserLocker(TypeMessage.LQI_REQ, key);
		getListLocker().add(lock);

		SendRs232Data(frame);

		return (Mgmt_LQI_rsp) waitResponse(lock, opcode, timeout);
	}

	public Status sendInterPANMessaSync(long timeout, InterPANMessage message) throws Exception {
		LOG.debug("Data_FreeScale.send_InterPAN");

		BigInteger dstAddress = null;

		switch ((int) message.getDstAddressMode()) {

		case GatewayConstants.EXTENDED_ADDRESS_MODE:
			dstAddress = message.getDestinationAddress().getIeeeAddress();
			break;

		case GatewayConstants.ADDRESS_MODE_SHORT:
			dstAddress = BigInteger.valueOf(message.getDestinationAddress().getNetworkAddress());
			break;

		case GatewayConstants.ADDRESS_MODE_ALIAS:
			throw new Exception("Not implemented: ADDRESS_MODE_ALIAS");

		default:
			throw new Exception("Unknown destination address mode");
		}

		Status status = new Status();

		ParserLocker lock = new ParserLocker();
		lock.setType(TypeMessage.INTERPAN);

		getListLocker().add(lock);

		SendRs232Data(makeByteArrayFromInterPANMessage(message));

		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);

		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {
			LOG.error("Timeout expired in send InterPANMessage");
			throw new GatewayException("Timeout expired in send InterPANMessage. No Confirm Received.");
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());

				throw new GatewayException("Error on  INTERPAN-DATA.Request. Status code:" + String.format("%02X", status.getCode())
						+ " Status Message: " + status.getMessage());

			}
			return status;
		}
	}

	public void destroy() {
		synchronized (destroy) {
			destroy = true;
		}
		clearBuffer();
	}

	public boolean getDestroy() {
		synchronized (destroy) {
			return destroy;
		}
	}

	public String MacGetPIBAttributeSync(long timeout, short _AttID) throws Exception {

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addByte((byte) _AttID);/* iId */
		frame.addByte((byte) 0x00);/* iIndex */
		frame = Set_SequenceStart_And_FSC(frame, FreescaleConstants.MacGetPIBAttributeRequest);
		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker();

		lock.setType(TypeMessage.MAC_GET);
		lock.set_Key(String.format("%02X", _AttID));
		Status status = new Status();

		getListLocker().add(lock);

		SendRs232Data(frame);
		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID)
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		status = lock.getStatus();

		if (getListLocker().contains(lock))
			getListLocker().remove(lock);

		if (status.getCode() == ParserLocker.INVALID_ID) {

			LOG.error("Timeout expired in MacGetPIBAttribute.Request");

			throw new GatewayException("Timeout expired in MacGetPIBAttribute.Request");
		} else {
			if (status.getCode() != 0) {
				LOG.info("Returned Status: {}", status.getCode());

				throw new GatewayException(
						"Error on MacGetPIBAttribute.Request. Status code: " + status.getCode() + " Status Message: " + status.getMessage());
			} else
				return (String) lock.get_objectOfResponse();
		}
	}

	private Object waitResponse(ParserLocker lock, short opcode, long timeout) throws GatewayException, InterruptedException {

		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID) {
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		}

		Status status = lock.getStatus();

		if (getListLocker().contains(lock)) {
			getListLocker().remove(lock);
		}

		if (status.getCode() == ParserLocker.INVALID_ID) {
			LOG.error("Timeout expired while waiting for " + lock.getType());
			throw new GatewayException("Timeout expired while waiting for " + lock.getType());
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());
				throw new GatewayException("Error returned from " + Utils.opcodeToString(opcode) + ": status code:" + status.getCode()
						+ " Status Message: " + status.getMessage());
			} else {
				return lock.get_objectOfResponse();
			}
		}
	}

	private Status waitStatus(ParserLocker lock, short opcode, long timeout) throws GatewayException, InterruptedException {

		if (lock.getStatus().getCode() == ParserLocker.INVALID_ID) {
			lock.getObjectLocker().poll(timeout, TimeUnit.MILLISECONDS);
		}

		Status status = lock.getStatus();

		if (getListLocker().contains(lock)) {
			getListLocker().remove(lock);
		}

		if (status.getCode() == ParserLocker.INVALID_ID) {
			LOG.error("Timeout expired while waiting for " + lock.getType());
			throw new GatewayException("Timeout expired while waiting for " + lock.getType());
		} else {
			if (status.getCode() != 0) {
				LOG.debug("Returned Status: {}", status.getCode());
				throw new GatewayException("Error returned from " + Utils.opcodeToString(opcode) + ": status code:" + status.getCode()
						+ " Status Message: " + status.getMessage());
			} else {
				return status;
			}
		}
	}
}
