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
import org.energy_home.jemma.javagal.layers.business.implementations.SerializationUtils;
import org.energy_home.jemma.javagal.layers.data.implementations.SerialPortConnectorJssc;
import org.energy_home.jemma.javagal.layers.data.implementations.SerialPortConnectorRxTx;
import org.energy_home.jemma.javagal.layers.data.implementations.Utils.DataManipulation;
import org.energy_home.jemma.javagal.layers.data.implementations.Utils.LogUtils;
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

	private final List<ParserLocker> listLocker;

	private byte[] rawnotprocessed = new byte[0];

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

		PropertiesManager configuration = getGal().getPropertiesManager();

		listLocker = Collections.synchronizedList(new LinkedList<ParserLocker>());

		dataFromSerialComm = new ArrayBlockingQueue<ByteArrayObject>(SIZE_ARRAY);
		/*
		 * We don't know in advance which comm library is installed into the system.
		 */
		boolean foundSerialLib = false;
		try {
			dongleRs232 = new SerialPortConnectorJssc(configuration.getzgdDongleUri(), configuration.getzgdDongleSpeed(), this);
			foundSerialLib = true;
		} catch (NoClassDefFoundError e) {
			LOG.warn("jSSC not found");
		}

		if (!foundSerialLib) {
			try {
				// then with jSSC
				dongleRs232 = new SerialPortConnectorRxTx(configuration.getzgdDongleUri(), configuration.getzgdDongleSpeed(), this);
				foundSerialLib = true;
			} catch (NoClassDefFoundError e) {
				LOG.warn("RxTx not found");
			}
		}

		if (!foundSerialLib) {
			throw new Exception("Error not found Rxtx or Jssc serial connector library");
		}

		RS232Filter.create(dongleRs232);

		INTERNAL_TIMEOUT = configuration.getCommandTimeoutMS();
		executor = Executors.newFixedThreadPool(configuration.getNumberOfThreadForAnyPool(), new ThreadFactory() {
			public Thread newThread(Runnable r) {
				return new Thread(r, "THPool-processMessages");
			}
		});

		if (executor instanceof ThreadPoolExecutor) {
			((ThreadPoolExecutor) executor).setKeepAliveTime(configuration.getKeepAliveThread(), TimeUnit.MINUTES);
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

	private ArrayBlockingQueue<ByteArrayObject> getDataFromSerialComm() {
		return dataFromSerialComm;
	}

	public void destroy() {
		synchronized (destroy) {
			destroy = true;
		}
		clearBuffer();
	}

	private List<ParserLocker> getListLocker() {
		return listLocker;
	}

	private void addParserLocker(ParserLocker lock) {
		synchronized (this) {
			this.listLocker.add(lock);
		}
	}

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

		short opcode = (short) DataManipulation.toIntFromShort(frame.getByte(0), frame.getByte(1));
		short status = (short) (frame.getByte(3) & 0xFF);

		long longAddress;

		switch (opcode) {

		/* APSDE-DATA.Indication */
		case FreescaleConstants.APSDEDataIndication:
			apsdeDataIndication(frame);
			break;

		/* INTERPAN-DATA.Indication */
		case FreescaleConstants.InterPANDataIndication:
			interpanDataIndication(frame);
			break;

		/* APSDE-DATA.Confirm */
		case FreescaleConstants.APSDEDataConfirm:
			apsdeDataConfirm(frame);
			break;

		/* INTERPAN-Data.Confirm */
		case FreescaleConstants.InterPANDataConfirm:
			interpanDataConfirm(frame);
			break;

		/* ZTC-Error.event */
		case FreescaleConstants.ZTCErrorevent:
			ztcErrorEvent(frame);
			break;

		/* ZDP-Mgmt_Nwk_Update.Notify */
		case FreescaleConstants.ZDPMgmt_Nwk_UpdateNotify:
			zdpMgmtNwkUpdateNotify(frame);
			break;

		/* ZDP-SimpleDescriptor.Response */
		case FreescaleConstants.ZDPSimpleDescriptorResponse:
			zdpSimpleDescriptorResponse(frame);
			break;

		/* APS-GetEndPointIdList.Confirm */
		case FreescaleConstants.APSGetEndPointIdListConfirm:
			NodeServices result = null;

			if (status == GatewayConstants.SUCCESS) {
				result = new NodeServices();
				short length = frame.getByte(4);
				for (int i = 0; i < length; i++) {
					ActiveEndpoints _ep = new ActiveEndpoints();
					_ep.setEndPoint((short) (frame.getByte(5 + i) & 0xFF));
					result.getActiveEndpoints().add(_ep);
				}
			}

			fireLocker(TypeMessage.GET_END_POINT_LIST, result, status);
			break;

		/* ZDP-BIND.Response */
		case FreescaleConstants.ZDPMgmtBindResponse:
			fireLocker(TypeMessage.ADD_BINDING, null, status);
			break;

		/* ZDP-UNBIND.Response */
		case FreescaleConstants.ZDPUnbindResponse:
			fireLocker(TypeMessage.REMOVE_BINDING, null, status);
			break;

		/* ZDP-Mgmt_Bind.Response */
		case FreescaleConstants.ZDPMgmt_BindResponse:
			zdpMgmtBindResponse(frame);
			break;

		/* APS-DeregisterEndPoint.Confirm */
		case FreescaleConstants.APSDeRegisterEndPointConfirm:
			fireLocker(TypeMessage.DEREGISTER_END_POINT, null, status);
			break;

		/* APS-ZDP-Mgmt_Lqi.Response */
		case FreescaleConstants.ZDPMgmtLqiResponse: {
			if (status == GatewayConstants.SUCCESS) {
				LOG.debug("Extracted ZDP-Mgmt_Lqi.Response with status[ {} ] ...waiting the related Indication ZDO:{} ", status,
						frame.toString());
			} else {
				LOG.error("Extracted ZDP-Mgmt_Lqi.Response with wrong status[" + status + "] " + frame.toString());
			}
			break;
		}

		/* ZTC-ReadExtAddr.Confirm */
		case FreescaleConstants.ZTCReadExtAddrConfirm:
			longAddress = frame.getLong(4);

			BigInteger _bi = new BigInteger(1, Utils.longToByteArray(longAddress));

			fireLocker(TypeMessage.READ_EXT_ADDRESS, _bi, status);
			break;

		/* ZDP-IEEE_addr.response */
		case FreescaleConstants.ZDPIeeeAddrResponse:

			longAddress = frame.getLong(4);

			Integer shortAddress = DataManipulation.toIntFromShort(frame.getByte(13), frame.getByte(12));

			_bi = new BigInteger(1, Utils.longToByteArray(longAddress));

			String key = String.format("%04X", shortAddress);

			fireLocker(TypeMessage.READ_IEEE_ADDRESS, key, _bi, status);
			break;

		/* ZDP-Active_EP_rsp.response */
		case FreescaleConstants.ZDPActiveEpResponse:
			zdpActiveEndPointResponse(frame);
			break;

		/* ZDP-StopNwkEx.Confirm */
		case FreescaleConstants.ZTCStopNwkExConfirm:
			zdpStopNwkExConfirm(frame);
			break;

		/* NLME-GET.Confirm */
		case FreescaleConstants.NLMEGetConfirm:
			key = String.format("%02X", (frame.getByte(4) & 0xFF));
			short len = (short) DataManipulation.toIntFromShort(frame.getByte(9), frame.getByte(8));
			byte[] _res = DataManipulation.subByteArray(frame.getArray(), 10, len + 9);
			if (len >= 2) {
				_res = DataManipulation.reverseBytes(_res);
			}
			String stringResult = DataManipulation.convertBytesToString(_res);

			fireLocker(TypeMessage.NMLE_GET, key, stringResult, status);
			break;

		/* APSME_GET.Confirm */
		case FreescaleConstants.APSMEGetConfirm: {
			key = String.format("%02X", (short) (frame.getByte(4)) & 0xFF);
			len = (short) DataManipulation.toIntFromShort(frame.getByte(9), frame.getByte(8));
			byte[] resultBytes = DataManipulation.subByteArray(frame.getArray(), 10, len + 9);

			if (len >= 2) {
				resultBytes = DataManipulation.reverseBytes(resultBytes);
			}

			DataManipulation.convertBytesToString(resultBytes);

			fireLocker(TypeMessage.APSME_GET, key, resultBytes, status);
			break;
		}

		/* MacGetPIBAttribute.Confirm */
		case FreescaleConstants.MacGetPIBAttributeConfirm:
			MacGetConfirm(frame);
			break;

		// ZDP-StartNwkEx.Confirm
		case FreescaleConstants.ZTCStartNwkExConfirm:
			fireLocker(TypeMessage.START_NETWORK, null, status);
			break;

		/* APS-RegisterEndPoint.Confirm */
		case FreescaleConstants.APSRegisterEndPointConfirm:
			fireLocker(TypeMessage.CONFIGURE_END_POINT, null, status);
			break;

		/* ZTC-ModeSelect.Confirm */
		case FreescaleConstants.ZTCModeSelectConfirm:
			fireLocker(TypeMessage.MODE_SELECT, null, status);
			break;

		/* BlackBox.WriteSAS.Confirm */
		case FreescaleConstants.BlackBoxWriteSASConfirm:
			fireLocker(TypeMessage.WRITE_SAS, null, status);
			break;

		/* ZTC-GetChannel.Confirm */
		case FreescaleConstants.ZTCGetChannelConfirm: {
			short shortResult = (short) frame.getByte(4);
			fireLocker(TypeMessage.CHANNEL_REQUEST, shortResult, status);
			break;
		}

		/* ZDP-NodeDescriptor.Response */
		case FreescaleConstants.ZDPNodeDescriptorResponse:
			zdpNodeDescriptorResponse(frame);
			break;

		/* NMLE-SET.Confirm */
		case FreescaleConstants.NMLESETConfirm:
			fireLocker(TypeMessage.NMLE_SET, null, status);
			break;

		/* APSME-SET.Confirm */
		case FreescaleConstants.APSMESetConfirm:
			fireLocker(TypeMessage.APSME_SET, null, status);
			break;

		/* ZDP-Mgmt_Permit_Join.response */
		case FreescaleConstants.ZDPMgmt_Permit_JoinResponse:
			fireLocker(TypeMessage.PERMIT_JOIN, null, status);
			break;

		/* APS-ClearDeviceKeyPairSet.Confirm */
		case FreescaleConstants.APSClearDeviceKeyPairSetConfirm:
			fireLocker(TypeMessage.CLEAR_DEVICE_KEY_PAIR_SET, null, status);
			;
			break;

		/* ZTC-ClearNeighborTableEntry.Confirm */
		case FreescaleConstants.ZTCClearNeighborTableEntryConfirm:
			fireLocker(TypeMessage.CLEAR_NEIGHBOR_TABLE_ENTRY, null, status);
			break;

		/* NLME-JOIN.Confirm */
		case FreescaleConstants.NLMEJOINConfirm:
			nlmeJoinConfirm(frame);
			break;

		/* ZDO-NetworkState.Event */
		case FreescaleConstants.ZDONetworkStateEvent: {
			LOG.debug("Extracted ZDO-NetworkState.Event: " + LogUtils.decodeNetworkStatusEvent(status));
			switch (status) {
			case 0x03:
				getGal().setGatewayStatus(GatewayStatus.GW_STARTING);
				break;

			case 0x04:
			case 0x05:
			case 0x10:
				getGal().setGatewayStatus(GatewayStatus.GW_RUNNING);
				break;

			case 0x09:
				getGal().setGatewayStatus(GatewayStatus.GW_STOPPING);
				break;

			case 0x0B:
				getGal().setGatewayStatus(GatewayStatus.GW_STOPPED);
				break;
			}
			break;
		}

		case FreescaleConstants.ZDPMgmtLeaveResponse:
		case FreescaleConstants.ZDPNwkProcessSecureFrameConfirm:
			LOG.debug(frame.toString());
			break;

		case FreescaleConstants.NLMENETWORKFORMATIONConfirm:
		case FreescaleConstants.NLMESTARTROUTERRequest:
		case FreescaleConstants.NLMESTARTROUTERConfirm:
		case FreescaleConstants.NWKProcessSecureFrameReport:
		case FreescaleConstants.NLMEENERGYSCANRequest:
		case FreescaleConstants.NLMEENERGYSCANconfirm:
		case FreescaleConstants.NLMENETWORKDISCOVERYRequest:
		case FreescaleConstants.NLMENetworkDiscoveryConfirm:
		case FreescaleConstants.NLMENETWORKFORMATIONRequest:
		case FreescaleConstants.NLMESetRequest:
		case FreescaleConstants.NLMENWKSTATUSIndication:
		case FreescaleConstants.NLMENwkStatusIndication:
			LOG.debug(frame.toString());
			break;

		case FreescaleConstants.MacStartRequest:
		case FreescaleConstants.MacStartConfirm:
		case FreescaleConstants.MacBeaconNotifyIndication:
		case FreescaleConstants.MacPollNotifyIndication:
		case FreescaleConstants.MacSetPIBAttributeConfirm:
		case FreescaleConstants.MacScanRequest:
		case FreescaleConstants.MacScanConfirm:
			LOG.debug(frame.toString());
			break;
		}
	}

	/**
	 * @param frame
	 * @throws Exception
	 */
	private void nlmeJoinConfirm(ByteArrayObject frame) throws Exception {
		short _status = (short) (frame.getByte(8) & 0xFF);
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
	 * @param frame
	 * @throws Exception
	 */
	private void zdpNodeDescriptorResponse(ByteArrayObject frame) throws Exception {

		short status = (short) (frame.getByte(3) & 0xFF);

		int _NWKAddressOfInterest = DataManipulation.toIntFromShort(frame.getByte(5), frame.getByte(4));
		Address _addressOfInterst = new Address();
		_addressOfInterst.setNetworkAddress(_NWKAddressOfInterest);
		NodeDescriptor node = new NodeDescriptor();

		/* First Byte */
		byte _first = frame.getByte(6);
		byte _Logical_byte = (byte) (_first & 0x07);/* Bits 0,1,2 */
		byte _ComplexDescriptorAvalilable = (byte) ((_first & 0x08) >> 3);/* Bit3 */
		byte _UserDescriptorAvalilable = (byte) ((_first & 0x0A) >> 4);/* Bit4 */

		switch (_Logical_byte) {
		case FreescaleConstants.LogicalType.Coordinator:
			node.setLogicalType(LogicalType.COORDINATOR);
			break;

		case FreescaleConstants.LogicalType.Router:
			node.setLogicalType(LogicalType.ROUTER);
			break;

		case FreescaleConstants.LogicalType.EndDevice:
			node.setLogicalType(LogicalType.END_DEVICE);
			break;

		default:
			throw new Exception("LogicalType is not valid value");
		}
		node.setComplexDescriptorAvailable((_ComplexDescriptorAvalilable == 1 ? true : false));
		node.setUserDescriptorAvailable((_UserDescriptorAvalilable == 1 ? true : false));

		/* Second Byte */
		byte _second = frame.getByte(7);
		/* Aps flags bits 0,1,2 */

		/*
		 * bits 3 , 4 , 5 , 6 , 7
		 */
		byte _FrequencyBand = (byte) ((_second & 0xF8) >> 0x03);

		switch (_FrequencyBand) {
		case 0x01:
			node.setFrequencyBand("868MHz");
			break;

		case 0x04:
			node.setFrequencyBand("900MHz");
			break;

		case 0x08:
			node.setFrequencyBand("2400MHz");
			break;

		default:
			node.setFrequencyBand("Reserved");
			break;
		}

		/* MACcapabilityFlags_BYTE Byte */
		byte _MACcapabilityFlags_BYTE = frame.getByte(8);
		MACCapability macCapabilities = new MACCapability();

		/* Bit0 */
		byte _AlternatePanCoordinator = (byte) (_MACcapabilityFlags_BYTE & 0x01);

		/* Bit1 */
		byte _DeviceIsFFD = (byte) ((_MACcapabilityFlags_BYTE & 0x02) >> 1);

		/* Bit2 */
		byte _MainsPowered = (byte) ((_MACcapabilityFlags_BYTE & 0x04) >> 2);

		/* Bit3 */
		byte _ReceiverOnWhenIdle = (byte) ((_MACcapabilityFlags_BYTE & 0x08) >> 3);
		// bit 4-5 reserved

		/* Bit6 */
		byte _SecuritySupported = (byte) ((_MACcapabilityFlags_BYTE & 0x40) >> 6);

		/* Bit7 */
		byte _AllocateAddress = (byte) ((_MACcapabilityFlags_BYTE & 0x80) >> 7);

		macCapabilities.setAlternatePanCoordinator((_AlternatePanCoordinator == 1 ? true : false));
		macCapabilities.setDeviceIsFFD((_DeviceIsFFD == 1 ? true : false));
		macCapabilities.setMainsPowered((_MainsPowered == 1 ? true : false));
		macCapabilities.setReceiverOnWhenIdle((_ReceiverOnWhenIdle == 1 ? true : false));
		macCapabilities.setSecuritySupported((_SecuritySupported == 1 ? true : false));
		macCapabilities.setAllocateAddress((_AllocateAddress == 1 ? true : false));

		node.setMACCapabilityFlag(macCapabilities);

		/* ManufacturerCode_BYTES */
		int _ManufacturerCode_BYTES = DataManipulation.toIntFromShort(frame.getByte(10), frame.getByte(9));
		node.setManufacturerCode(_ManufacturerCode_BYTES);

		/* MaximumBufferSize_BYTE */
		short _MaximumBufferSize_BYTE = frame.getByte(11);
		node.setMaximumBufferSize(_MaximumBufferSize_BYTE);

		/* MaximumTransferSize_BYTES */
		int _MaximumTransferSize_BYTES = DataManipulation.toIntFromShort(frame.getByte(13), frame.getByte(12));
		node.setMaximumIncomingTransferSize(_MaximumTransferSize_BYTES);

		/* ServerMask_BYTES */
		int _ServerMask_BYTES = DataManipulation.toIntFromShort(frame.getByte(15), frame.getByte(14));
		ServerMask serverMask = new ServerMask();

		/* Bit0 */
		byte _PrimaryTrustCenter = (byte) (_ServerMask_BYTES & 0x01);

		/* Bit1 */
		byte _BackupTrustCenter = (byte) ((_ServerMask_BYTES & 0x02) >> 1);

		/* Bit2 */
		byte _PrimaryBindingTableCache = (byte) ((_ServerMask_BYTES & 0x04) >> 2);

		/* Bit3 */
		byte _BackupBindingTableCache = (byte) ((_ServerMask_BYTES & 0x08) >> 3);

		/* Bit4 */
		byte _PrimaryDiscoveryCache = (byte) ((_ServerMask_BYTES & 0x10) >> 4);

		/* Bit5 */
		byte _BackupDiscoveryCache = (byte) ((_ServerMask_BYTES & 0x20) >> 5);

		serverMask.setPrimaryTrustCenter((_PrimaryTrustCenter == 1 ? true : false));
		serverMask.setBackupTrustCenter((_BackupTrustCenter == 1 ? true : false));
		serverMask.setPrimaryBindingTableCache((_PrimaryBindingTableCache == 1 ? true : false));
		serverMask.setBackupBindingTableCache((_BackupBindingTableCache == 1 ? true : false));
		serverMask.setPrimaryDiscoveryCache((_PrimaryDiscoveryCache == 1 ? true : false));
		serverMask.setBackupDiscoveryCache((_BackupDiscoveryCache == 1 ? true : false));
		node.setServerMask(serverMask);

		/* MaximumOutTransferSize_BYTES */
		int _MaximumOutTransferSize_BYTES = DataManipulation.toIntFromShort(frame.getByte(17), frame.getByte(16));
		node.setMaximumOutgoingTransferSize(_MaximumOutTransferSize_BYTES);

		/* CapabilityField_BYTES */
		byte _CapabilityField_BYTES = frame.getByte(18);
		DescriptorCapability _DescriptorCapability = new DescriptorCapability();

		/* Bit0 */
		byte _ExtendedActiveEndpointListAvailable = (byte) (_CapabilityField_BYTES & 0x01);

		/* Bit1 */
		byte _ExtendedSimpleDescriptorListAvailable = (byte) ((_CapabilityField_BYTES & 0x02) >> 1);

		_DescriptorCapability.setExtendedActiveEndpointListAvailable((_ExtendedActiveEndpointListAvailable == 1 ? true : false));
		_DescriptorCapability.setExtendedSimpleDescriptorListAvailable((_ExtendedSimpleDescriptorListAvailable == 1 ? true : false));

		node.setDescriptorCapabilityField(_DescriptorCapability);

		String key = String.format("%04X", _NWKAddressOfInterest);

		fireLocker(TypeMessage.NODE_DESCRIPTOR, key, node, status);
	}

	private void MacGetConfirm(ByteArrayObject frame) {
		LOG.debug("Extracted MacGetPIBAttribute.Confirm: {}", frame.toString());
		String key = String.format("%02X", (short) (frame.getByte(4) & 0xFF));
		// Found MacGetPIBAttribute.Confirm. Remove the lock
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.MAC_GET) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)
						&& (pl.get_Key().equalsIgnoreCase(key))) {
					short _Length = (short) DataManipulation.toIntFromShort(frame.getByte(9), frame.getByte(8));
					byte[] _res = DataManipulation.subByteArray(frame.getArray(), 10, _Length + 9);
					if (_Length >= 2)
						_res = DataManipulation.reverseBytes(_res);

					pl.getStatus().setCode((short) (frame.getByte(3) & 0xFF));
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

	private void zdpStopNwkExConfirm(ByteArrayObject frame) {
		LOG.debug("Extracted ZDP-StopNwkEx.Confirm: {}", frame.toString());

		short status = (short) (frame.getByte(3) & 0xFF);

		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.STOP_NETWORK) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

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
	 * Parse the ZDPActiveEndPoinResponse frame.
	 * 
	 * @param frame
	 *          The frame to parse
	 */
	private void zdpActiveEndPointResponse(ByteArrayObject frame) {

		LOG.debug("Extracted ZDP-Active_EP_rsp.response: {}", frame.toString());

		short status = (short) (frame.getByte(3) & 0xFF);
		Address _add = new Address();

		_add.setNetworkAddress(DataManipulation.toIntFromShort(frame.getByte(5), frame.getByte(4)));

		String key = String.format("%04X", _add.getNetworkAddress());
		List<Short> result = null;

		NodeServices _node = new NodeServices();
		_node.setAddress(_add);

		switch (status) {
		case 0x00:
			result = new ArrayList<Short>();
			int _EPCount = frame.getByte(6);

			for (int i = 0; i < _EPCount; i++) {
				result.add((short) (frame.getByte(7 + i) & 0xFF));
				ActiveEndpoints _aep = new ActiveEndpoints();
				_aep.setEndPoint((short) (frame.getByte(7 + i) & 0xFF));
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

		fireLocker(TypeMessage.ACTIVE_EP, key, result, status);
	}

	private void zdpMgmtBindResponse(ByteArrayObject frame) {
		LOG.debug(frame.toString());

		short status = (short) (frame.getByte(3) & 0xFF);

		BindingList result = new BindingList();

		if (status == GatewayConstants.SUCCESS) {
			short length = (short) (frame.getByte(6) & 0xFF);
			int index = 6;
			for (int i = 0; i < length; i++) {
				Binding binding = new Binding();

				long src_longAddress = frame.getLong(index + 1);

				short _srcEP = (short) (frame.getByte(index + 9) & 0xFF);

				int _cluster = DataManipulation.toIntFromShort(frame.getByte(index + 11), frame.getByte(index + 10));

				short dstMode = (short) (frame.getByte(index + 12) & 0xFF);

				Device device = new Device();

				if (dstMode == 0x03) {
					long dst_longAddress = frame.getLong(index + 13);

					short _dstEP = (short) (frame.getByte(index + 21) & 0xFF);

					// _dev.setAddress(BigInteger.valueOf(dst_longAddress));
					device.setAddress(new BigInteger(1, Utils.longToByteArray(dst_longAddress)));
					device.setEndpoint(_dstEP);
					index = index + 21;
				} else if (dstMode == 0x01) {
					int _groupId = DataManipulation.toIntFromShort(frame.getByte(index + 14), frame.getByte(index + 13));
					device.setAddress(BigInteger.valueOf(_groupId));
					index = index + 10;
				}

				binding.setClusterID(_cluster);
				binding.setSourceEndpoint(_srcEP);

				binding.setSourceIEEEAddress(new BigInteger(1, Utils.longToByteArray(src_longAddress)));
				binding.getDeviceDestination().add(device);

				result.getBinding().add(binding);
			}
		}

		fireLocker(TypeMessage.GET_BINDINGS, result, status);
	}

	private void zdpSimpleDescriptorResponse(ByteArrayObject frame) {
		LOG.debug("Extracted ZDP-SimpleDescriptor.Response: {}", frame.toString());
		/* Address + EndPoint */
		Address _add = new Address();
		_add.setNetworkAddress(DataManipulation.toIntFromShort(frame.getByte(5), frame.getByte(4)));
		short EndPoint = (short) (frame.getByte(7) & 0xFF);
		String Key = String.format("%04X", _add.getNetworkAddress()) + String.format("%02X", EndPoint);
		// Found ZDP-SimpleDescriptor.Response. Remove the lock
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				/* Address + EndPoint */
				LOG.debug("ZDP-SimpleDescriptor.Response Sent Key: {} - Received Key: {}", pl.get_Key(), Key);

				if ((pl.getType() == TypeMessage.GET_SIMPLE_DESCRIPTOR) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)
						&& (pl.get_Key().equalsIgnoreCase(Key))) {

					pl.getStatus().setCode((short) (frame.getByte(3) & 0xFF));
					ServiceDescriptor _toRes = new ServiceDescriptor();
					if (pl.getStatus().getCode() == GatewayConstants.SUCCESS) {
						SimpleDescriptor _sp = new SimpleDescriptor();
						_sp.setApplicationProfileIdentifier(DataManipulation.toIntFromShort(frame.getByte(9), frame.getByte(8)));
						_sp.setApplicationDeviceIdentifier(DataManipulation.toIntFromShort(frame.getByte(11), frame.getByte(10)));
						_sp.setApplicationDeviceVersion((short) frame.getByte(12));
						int _index = 14;
						short _numInpCluster = (short) (frame.getByte(13) & 0xFF);
						for (int i = 0; i < _numInpCluster; i++) {
							_sp.getApplicationInputCluster()
									.add(DataManipulation.toIntFromShort(frame.getByte(_index + 1), frame.getByte(_index)));
							_index = _index + 2;
						}

						short _numOutCluster = (short) (frame.getByte(_index++) & 0xFF);

						for (int i = 0; i < _numOutCluster; i++) {
							_sp.getApplicationOutputCluster()
									.add(DataManipulation.toIntFromShort(frame.getByte(_index + 1), frame.getByte(_index)));
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
	 * @param frame
	 */
	private void zdpMgmtNwkUpdateNotify(ByteArrayObject frame) {
		LOG.debug("Extracted ZDP-Mgmt_Nwk_Update.Notify: {}", frame.toString());

		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == TypeMessage.NWK_UPDATE) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					EnergyScanResult _result = new EnergyScanResult();

					int _address = DataManipulation.toIntFromShort(frame.getByte(4), frame.getByte(3));

					short _status = (short) (frame.getByte(5) & 0xFF);
					if (_status == GatewayConstants.SUCCESS) {
						byte[] _scannedChannel = new byte[4];
						_scannedChannel[0] = frame.getByte(9);
						_scannedChannel[1] = frame.getByte(8);
						_scannedChannel[2] = frame.getByte(7);
						_scannedChannel[3] = frame.getByte(6);

						int _totalTrasmission = DataManipulation.toIntFromShort(frame.getByte(11), frame.getByte(10));

						int _trasmissionFailure = DataManipulation.toIntFromShort(frame.getByte(13), frame.getByte(12));

						short _scannedChannelListCount = (short) (frame.getByte(14) & 0xFF);
						for (int i = 0; i < _scannedChannelListCount; i++) {
							ScannedChannel _sc = new ScannedChannel();
							// _sc.setChannel(value)
							_sc.setEnergy(frame.getByte(15 + i));

							_result.getScannedChannel().add(_sc);
						}

						pl.getStatus().setCode((short) (frame.getByte(7) & 0xFF));
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
	 * @param frame
	 */
	private void ztcErrorEvent(ByteArrayObject frame) {
		byte len = (byte) frame.getByte(2);
		String MessageStatus = "";
		if (len > 0) {
			short status = (short) (frame.getByte(3) & 0xFF);
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
		LOG.error(logMessage + " from " + frame.toString());
	}

	/**
	 * @param frame
	 */
	private void interpanDataConfirm(ByteArrayObject frame) {
		LOG.debug("Extracted INTERPAN-Data.Confirm: {}", frame.toString());
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {

				if ((pl.getType() == TypeMessage.INTERPAN) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.getStatus().setCode((short) (frame.getByte(4) & 0xFF));
					try {
						if (pl.getObjectLocker().size() == 0)
							pl.getObjectLocker().put((byte) 0);
					} catch (InterruptedException e) {

					}
				}
			}
		}
	}

	private void apsdeDataConfirm(ByteArrayObject frame) {

		/* DestAddress + DestEndPoint + SourceEndPoint */
		/* Marco Removed in order to increase the performance */

		long destAddress = frame.getLong(4);
		short destEndPoint = ((short) (frame.getByte(12) & 0xFF));
		short sourceEndPoint = ((short) (frame.getByte(13) & 0xFF));

		String Key = String.format("%016X", destAddress) + String.format("%02X", destEndPoint) + String.format("%02X", sourceEndPoint);

		short status = (short) (frame.getByte(14) & 0xFF);

		LOG.debug("APSDE-DATA.Confirm Status " + LogUtils.decodeApsdeDataConfirmStatus(status));
	}

	private void interpanDataIndication(ByteArrayObject frame) {
		final InterPANMessageEvent messageEvent = new InterPANMessageEvent();
		short srcAddressMode = (short) (frame.getByte(3) & 0xFF);
		messageEvent.setSrcAddressMode((long) srcAddressMode);
		messageEvent.setSrcPANID(DataManipulation.toIntFromShort(frame.getByte(5), frame.getByte(4)));

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
			address.setNetworkAddress(DataManipulation.toIntFromShort(frame.getByte(7), frame.getByte(6)));
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
			address.setNetworkAddress(DataManipulation.toIntFromShort(frame.getByte(7), frame.getByte(6)));

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

		short dstAddressMode = frame.getByte(14);
		messageEvent.setDstAddressMode((long) dstAddressMode);
		messageEvent.setDstPANID(DataManipulation.toIntFromShort(frame.getByte(16), frame.getByte(15)));

		switch (dstAddressMode) {
		case 0x00:
			// Reserved (No source address supplied)
			LOG.debug("Message Discarded: found reserved 0x00 as Destination Address Mode ");
			// Error found, we don't proceed and discard the
			// message
			return;
		case 0x01:
			address.setNetworkAddress(DataManipulation.toIntFromShort(frame.getByte(18), frame.getByte(17)));
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
			address.setNetworkAddress(DataManipulation.toIntFromShort(frame.getByte(18), frame.getByte(17)));
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

		messageEvent.setProfileID(DataManipulation.toIntFromShort(frame.getByte(20), frame.getByte(19)));
		messageEvent.setClusterID(DataManipulation.toIntFromShort(frame.getByte(22), frame.getByte(21)));

		int asduLength = (frame.getByte(23) & 0xFF);
		messageEvent.setASDULength(asduLength);
		messageEvent.setASDU(DataManipulation.subByteArray(frame.getArray(), 27, asduLength + 27));
		messageEvent.setLinkQuality((short) (frame.getByte(asduLength + 28) & 0xFF));

		/* Gestione callback */
		getGal().getMessageManager().InterPANMessageIndication(messageEvent);
		getGal().get_gatewayEventManager().notifyInterPANMessageEvent(messageEvent);
	}

	private void apsdeDataIndication(ByteArrayObject frame) {

		LOG.debug(frame.toString());

		WrapperWSNNode node = null;
		final APSMessageEvent messageEvent = new APSMessageEvent();
		messageEvent.setDestinationAddressMode((long) (frame.getByte(3) & 0xFF));
		BigInteger _ieee = null;
		Address destinationAddress = new Address();

		switch (messageEvent.getDestinationAddressMode().shortValue()) {
		case 0x00:
			/* Reserved (No source address supplied) */
			LOG.debug("Message Discarded: found reserved 0x00 as Destination Address Mode ");
			return;
		case 0x01:
			/*
			 * Value16bitgroupfordstAddr (DstEndpoint not present) No destination end
			 * point (so FF broadcast), present short address on 2 bytes
			 */
			destinationAddress.setNetworkAddress(DataManipulation.toIntFromShort(frame.getByte(5), frame.getByte(4)));
			messageEvent.setDestinationAddress(destinationAddress);
			messageEvent.setDestinationEndpoint((short) 0xFF);
			break;

		case 0x02:
			/*
			 * Value16bitAddrandDstEndpoint (16 bit address supplied)
			 */
			destinationAddress.setNetworkAddress(DataManipulation.toIntFromShort(frame.getByte(5), frame.getByte(4)));
			messageEvent.setDestinationAddress(destinationAddress);
			messageEvent.setDestinationEndpoint((short) (frame.getByte(6) & 0xFF));
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

		messageEvent.setSourceAddressMode((long) (frame.getByte(7) & 0xFF));

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
			sourceAddress.setNetworkAddress(DataManipulation.toIntFromShort(frame.getByte(9), frame.getByte(8)));
			messageEvent.setSourceAddress(sourceAddress);
			messageEvent.setSourceEndpoint((short) 0xFF);
			break;

		case 0x02:
			/*
			 * Value16bitAddrandDstEndpoint (16 bit address supplied)
			 */
			sourceAddress.setNetworkAddress(DataManipulation.toIntFromShort(frame.getByte(9), frame.getByte(8)));
			messageEvent.setSourceAddress(sourceAddress);
			messageEvent.setSourceEndpoint((short) (frame.getByte(10) & 0xFF));
			break;

		default:
			LOG.error("Message Discarded: not valid Source Address Mode");
			return;
		}

		messageEvent.setProfileID(DataManipulation.toIntFromShort(frame.getByte(12), frame.getByte(11)));
		messageEvent.setClusterID(DataManipulation.toIntFromShort(frame.getByte(14), frame.getByte(13)));

		int lastAsdu = 16 + frame.getByte(15) - 1;

		messageEvent.setData(DataManipulation.subByteArray(frame.getArray(), 16, lastAsdu));
		messageEvent.setAPSStatus((frame.getByte(lastAsdu + 1) & 0xFF));

		boolean wasBroadcast = ((frame.getByte(lastAsdu + 2) & 0xFF) == 0x01) ? true : false;

		/*
		 * ASK Jump WasBroadcast Security Status
		 */
		switch ((short) (frame.getByte(lastAsdu + 3) & 0xFF)) {
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

		messageEvent.setLinkQuality((short) (frame.getByte(lastAsdu + 4) & 0xFF));

		messageEvent.setRxTime((long) DataManipulation.toIntFromShort(frame.getByte((lastAsdu + 8)), frame.getByte((lastAsdu + 5))));

		if ((getGal().getGatewayStatus() == GatewayStatus.GW_RUNNING) && getGal().get_GalNode() != null) {

			if (wasBroadcast) {
				LOG.info("BROADCAST MESSAGE");
			} else {

				sourceAddress = messageEvent.getSourceAddress();

				node = updateNodeIfExist(messageEvent, sourceAddress);

				if (node == null) {
					return;
				}

				synchronized (node) {
					Address address = node.get_node().getAddress();

					/* lets copy the address and store it as sourceAddress */
					sourceAddress = SerializationUtils.clone(address);
				}

				if (sourceAddress.getIeeeAddress() == null) {
					LOG.error("Message discarded IEEE source address not found for Short address:"
							+ String.format("%04X", messageEvent.getSourceAddress().getNetworkAddress()) + " -- ProfileID: "
							+ String.format("%04X", messageEvent.getProfileID()) + " -- ClusterID: "
							+ String.format("%04X", messageEvent.getClusterID()));
					return;
				}

				if (sourceAddress.getNetworkAddress() == null) {
					LOG.error("Message discarded short source address not found for Ieee address:"
							+ String.format("%16X", sourceAddress.getIeeeAddress()) + " -- ProfileID: "
							+ String.format("%04X", messageEvent.getProfileID()) + " -- ClusterID: "
							+ String.format("%04X", messageEvent.getClusterID()));
					return;
				}
			}
		} else {
			return;
		}

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
				_header.addByte(data[0]); // Frame control
				_header.addByte(data[1]); // Manufacturer Code(1/2)
				_header.addByte(data[2]); // Manufacturer Code(2/2)
				_header.addByte(data[3]); // Transaction sequence
				// number
				_header.addByte(data[4]); // Command Identifier
				for (int i = 5; i < data.length; i++)
					_payload.addByte(data[i]);
			} else {
				_header.addByte(data[0]); // Frame control
				_header.addByte(data[1]); // Transaction sequence
				// number
				_header.addByte(data[2]); // Command Identifier
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

	protected void sendFrame(final ByteArrayObject frame) {
		RS232Filter.getInstance().write(frame);
	}

	protected void sendFrame(ParserLocker lock, ByteArrayObject frame) {
		addParserLocker(lock);
		RS232Filter.getInstance().write(frame);
	}

	// Set an APS information base (AIB) attribute.
	public Status APSME_SETSync(long timeout, short attributeId, String value) throws GatewayException, Exception {

		short opcode = FreescaleConstants.APSMESetRequest;

		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addByte(attributeId);
		frame.addByte(0x00);
		frame.addByte(0x00);
		frame.addByte(0x00);

		for (byte x : DataManipulation.hexStringToByteArray(value)) {
			frame.addByte(x);
		}

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.APSME_SET);

		sendFrame(lock, frame);

		return waitStatus(lock, opcode, timeout);
	}

	// Get APS information base (AIB) attributes.
	public String APSME_GETSync(long timeout, short attrId) throws Exception {

		short opcode = FreescaleConstants.APSMEGetRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addByte(attrId);/* iId */
		frame.addByte(0x00);/* iIndex */
		frame.addByte(0x00);/* iEntries */
		frame.addByte(0x00);/* iEntrySize */

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.APSME_GET, String.format("%02X", attrId));

		sendFrame(lock, frame);

		return (String) waitResponse(lock, opcode, timeout);
	}

	// Get network information base Attributes.
	public String NMLE_GetSync(long timeout, short attrId, short iEntry) throws Exception {

		short opcode = FreescaleConstants.NLMEGetRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addByte(attrId);/* iId */
		frame.addByte(0x00);/* iIndex */
		frame.addByte(iEntry);/* iEntries */
		frame.addByte(0x00);/* iEntrySize */

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.NMLE_GET, String.format("%02X", attrId));

		sendFrame(lock, frame);

		return (String) waitResponse(lock, opcode, timeout);
	}

	public Status stopNetworkSync(long timeout) throws Exception, GatewayException {

		short opcode = FreescaleConstants.ZTCStopNwkExRequest;
		ByteArrayObject frame = new ByteArrayObject(false);

		/*
		 * Stop Mode AnnounceStop (Stops after announcing it is leaving the
		 * network.)
		 */
		frame.addByte(0x01);

		/*
		 * Reset binding/group tables, node type, PAN ID etc to ROM state.
		 */
		frame.addByte(0x00);

		/* Restart after stopping. */
		frame.addByte(0x00);

		/* Writes NVM upon stop. */
		frame.addByte(0xFF);

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug("ZDP-StopNwkEx.Request: {}", frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.STOP_NETWORK);

		sendFrame(lock, frame);

		return waitStatus(lock, opcode, timeout);
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
			// addParserLocker(lock);
			sendFrame(makeByteArrayFromApsMessage(message));

			status.setCode((short) GatewayConstants.SUCCESS);
			return status;
		} else {
			throw new GatewayException("Error on APSDE-DATA.Request.Request. Destination address is null");
		}
	}

	public short configureEndPointSync(long timeout, SimpleDescriptor desc) throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.APSRegisterEndPointRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addByte(desc.getEndPoint().byteValue());/* End Point */

		frame.addShort(desc.getApplicationProfileIdentifier().shortValue());
		frame.addShort(desc.getApplicationDeviceIdentifier().shortValue());

		/* DeviceVersion */
		frame.addByte(desc.getApplicationDeviceVersion().byteValue());

		/* ClusterInputSize */
		frame.addByte((byte) desc.getApplicationInputCluster().size());
		if (desc.getApplicationInputCluster().size() > 0) {
			for (Integer x : desc.getApplicationInputCluster())
				frame.addShort(x.shortValue());
		}

		/* ClusterOutputSize */
		frame.addByte((byte) desc.getApplicationOutputCluster().size());

		if (desc.getApplicationOutputCluster().size() > 0) {
			for (Integer x : desc.getApplicationOutputCluster())
				frame.addShort(x.shortValue());
		}

		/* Maximum Window Size */
		frame.addByte((byte) 0x01);

		frame.addSequenceStartAndFSC(opcode);

		/* APS-RegisterEndPoint.Request */
		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.CONFIGURE_END_POINT);

		sendFrame(lock, frame);

		waitStatus(lock, opcode, timeout);

		return desc.getEndPoint();
	}

	public ByteArrayObject makeByteArrayFromApsMessage(APSMessage apsMessage) throws Exception {
		byte[] data = apsMessage.getData();

		ByteArrayObject frame = new ByteArrayObject(false);

		byte dam = apsMessage.getDestinationAddressMode().byteValue();

		frame.addByte(dam);

		Address address = apsMessage.getDestinationAddress();

		switch (dam) {
		case GatewayConstants.ADDRESS_MODE_SHORT:
			byte[] networkAddress = DataManipulation.toByteVect(address.getNetworkAddress(), 8);
			frame.addBytes(networkAddress);
			break;

		case GatewayConstants.EXTENDED_ADDRESS_MODE:
			byte[] ieeeAddress = DataManipulation.toByteVect(address.getIeeeAddress(), 8);
			frame.addBytes(ieeeAddress);
			break;

		case GatewayConstants.ADDRESS_MODE_ALIAS:
			throw new UnsupportedOperationException("Address Mode Alias");

		default:
			throw new Exception("Address Mode undefined!");

		}

		frame.addByte((byte) apsMessage.getDestinationEndpoint());
		frame.addShort(apsMessage.getProfileID().shortValue());
		frame.addShort((short) apsMessage.getClusterID());
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

		frame.addSequenceStartAndFSC(FreescaleConstants.APSDEDataRequest);

		LOG.debug("Write APS on: {} Message: {}", System.currentTimeMillis(), frame.toString());

		return frame;
	}

	public Status SetModeSelectSync(long timeout) throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZTCModeSelectRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addByte(0x01);/* UART Tx Blocking */
		frame.addByte(0x02);/* MCPS */
		frame.addByte(0x02);/* MLME */
		frame.addByte(0x02);/* ASP */
		frame.addByte(0x02);/* NLDE */
		frame.addByte(0x02);/* NLME */
		frame.addByte(0x02);/* APSDE */
		frame.addByte(0x02);/* AFDE */
		frame.addByte(0x02);/* APSME */
		frame.addByte(0x02);/* ZDP */
		frame.addByte(0x00);/* HealthCare */

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.MODE_SELECT);

		sendFrame(lock, frame);

		return waitStatus(lock, opcode, timeout);
	}

	public Status startGatewayDeviceSync(long timeout, StartupAttributeInfo sai) throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZTCStartNwkExRequest;

		PropertiesManager configuration = getGal().getPropertiesManager();

		Status statusWriteSAS = writeSasSync(timeout, sai);

		if (statusWriteSAS.getCode() == 0) {

			LOG.info("Starting Network...");

			LogicalType devType = configuration.getSturtupAttributeInfo().getDeviceType();
			ByteArrayObject frame = new ByteArrayObject(false);

			switch (devType) {

			case CURRENT:
				throw new Exception("LogicalType not Valid!");

			case COORDINATOR:
				frame.addByte(FreescaleConstants.DeviceType.Coordinator);
				LOG.debug("DeviceType == COORDINATOR");
				break;

			case END_DEVICE:
				frame.addByte(FreescaleConstants.DeviceType.EndDevice);
				LOG.debug("DeviceType == ENDDEVICE");
				break;

			case ROUTER:
				frame.addByte(FreescaleConstants.DeviceType.Router);
				LOG.debug("DeviceType == ROUTER");
				break;
			}

			LOG.debug("StartupSet value read from PropertiesManager: {}", configuration.getStartupSet());

			LOG.debug("StartupControlMode value read from PropertiesManager: {}",
					configuration.getSturtupAttributeInfo().getStartupControl().byteValue());

			frame.addByte(configuration.getStartupSet());
			frame.addByte(configuration.getSturtupAttributeInfo().getStartupControl().byteValue());

			frame.addSequenceStartAndFSC(opcode);

			LOG.debug("Start Network command: {} ---Timeout: {}", frame.toString(), timeout);

			ParserLocker lock = new ParserLocker(TypeMessage.START_NETWORK);

			sendFrame(lock, frame);

			Status status = waitStatus(lock, opcode, timeout);

			if (status.getCode() == 0x00) {
				getGal().setGatewayStatus(GatewayStatus.GW_STARTED);
			}

			return status;
		} else {
			return statusWriteSAS;
		}
	}

	private Status writeSasSync(long timeout, StartupAttributeInfo sai) throws InterruptedException, Exception {

		short opcode = FreescaleConstants.BlackBoxWriteSAS;

		if (sai.getChannelMask() == null) {
			sai = getGal().getPropertiesManager().getSturtupAttributeInfo();
		}

		LogicalType devType = sai.getDeviceType();

		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addShort(sai.getShortAddress().shortValue());

		/* Extended PanID */
		byte[] ExtendedPaniId = DataManipulation.toByteVect(sai.getExtendedPANId(), 8);
		frame.addBytes(ExtendedPaniId);

		LOG.debug("Extended PanID: {}", DataManipulation.convertBytesToString(ExtendedPaniId));

		/* Extended APS Use Extended PAN Id */
		byte[] APSUseExtendedPANId = DataManipulation.toByteVect(BigInteger.ZERO, 8);
		frame.addBytes(APSUseExtendedPANId);

		LOG.info("APS Use Extended PAN Id: {}", DataManipulation.convertBytesToString(APSUseExtendedPANId));

		frame.addShort(sai.getPANId().shortValue());
		byte[] _channel = Utils.buildChannelMask(sai.getChannelMask().shortValue());

		LOG.debug("Channel readed from PropertiesManager: {}", sai.getChannelMask());

		LOG.debug("Channel after conversion: {}", DataManipulation.convertArrayBytesToString(_channel));
		frame.addBytes(_channel);
		frame.addByte(sai.getProtocolVersion().byteValue());
		frame.addByte(sai.getStackProfile().byteValue());
		frame.addByte(sai.getStartupControl().byteValue());

		/* TrustCenterAddress */
		byte[] TrustCenterAddress = DataManipulation.toByteVect(sai.getTrustCenterAddress(), 8);
		LOG.debug("TrustCenterAddress:" + DataManipulation.convertBytesToString(TrustCenterAddress));

		frame.addBytes(TrustCenterAddress);

		/* TrustCenterMasterKey */
		byte[] TrustCenterMasterKey = (devType == LogicalType.COORDINATOR) ? sai.getTrustCenterMasterKey()
				: DataManipulation.toByteVect(BigInteger.ZERO, 16);
		LOG.debug("TrustCenterMasterKey: {}", DataManipulation.convertBytesToString(TrustCenterMasterKey));

		frame.addBytes(TrustCenterMasterKey);

		/* NetworKey */
		byte[] NetworKey = (devType == LogicalType.COORDINATOR) ? sai.getNetworkKey()
				: DataManipulation.toByteVect(BigInteger.ZERO, 16);
		LOG.debug("NetworKey: {}", DataManipulation.convertBytesToString(NetworKey));

		frame.addBytes(NetworKey);
		frame.addByte((sai.isUseInsecureJoin()) ? ((byte) 0x01) : ((byte) 0x00));

		/* PreconfiguredLinkKey */
		byte[] PreconfiguredLinkKey = sai.getPreconfiguredLinkKey();
		LOG.debug("PreconfiguredLinkKey: {}", DataManipulation.convertBytesToString(PreconfiguredLinkKey));

		for (byte b : PreconfiguredLinkKey)
			frame.addByte(b);

		frame.addByte(sai.getNetworkKeySeqNum().byteValue());
		frame.addByte((byte) 0x01);
		frame.addShort(sai.getNetworkManagerAddress().shortValue());
		frame.addByte(sai.getScanAttempts().byteValue());
		frame.addShort(sai.getTimeBetweenScans().shortValue());
		frame.addShort(sai.getRejoinInterval().shortValue());
		frame.addShort(sai.getMaxRejoinInterval().shortValue());
		frame.addShort(sai.getIndirectPollRate().shortValue());
		frame.addByte(sai.getParentRetryThreshold().byteValue());
		frame.addByte((sai.isConcentratorFlag()) ? ((byte) 0x01) : ((byte) 0x00));
		frame.addByte(sai.getConcentratorRadius().byteValue());
		frame.addByte(sai.getConcentratorDiscoveryTime().byteValue());

		frame.addSequenceStartAndFSC(opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.WRITE_SAS);

		sendFrame(lock, frame);

		return waitStatus(lock, opcode, timeout);
	}

	public Status permitJoinSync(long timeout, Address addrOfInterest, short duration, byte TCSignificance)
			throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.NLMEPermitJoiningRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addShort(addrOfInterest.getNetworkAddress().shortValue());
		frame.addByte(duration);
		frame.addByte(TCSignificance); /* TCSignificant */

		frame.addSequenceStartAndFSC(opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.PERMIT_JOIN);

		sendFrame(lock, frame);

		return waitStatus(lock, opcode, timeout);
	}

	public Status permitJoinAllSync(long timeout, Address addrOfInterest, short duration, byte TCSignificance)
			throws IOException, Exception {

		short opcode = FreescaleConstants.NLMEPermitJoiningRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addShort(addrOfInterest.getNetworkAddress().shortValue());
		frame.addByte(duration);
		frame.addByte(TCSignificance); /* TCSignificant */

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug("Permit Join command: {}", frame.toString());

		sendFrame(frame);

		Status status = new Status();
		status.setCode((short) GatewayConstants.SUCCESS);

		return status;
	}

	public short getChannelSync(long timeout) throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZTCGetChannelRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug("ZTC-GetChannel.Request: {}", frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.CHANNEL_REQUEST);

		sendFrame(lock, frame);

		return (Short) waitResponse(lock, opcode, timeout);
	}

	public BigInteger readExtAddressGal(long timeout) throws GatewayException, Exception {

		short opcode = FreescaleConstants.ZTCReadExtAddrRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug("ZTC-ReadExtAddr.Request: {}", frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.READ_EXT_ADDRESS);

		sendFrame(lock, frame);

		return (BigInteger) waitResponse(lock, opcode, timeout);
	}

	public BigInteger readExtAddress(long timeout, Integer shortAddress) throws GatewayException, Exception {

		short opcode = FreescaleConstants.ZDPIeeeAddrRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addShort(shortAddress.shortValue());
		frame.addShort(shortAddress.shortValue());
		frame.addByte((byte) 0x01); /* Request Type */
		frame.addByte((byte) 0x00); /* StartIndex */

		frame.addSequenceStartAndFSC(opcode);

		LOG.info(frame.toString());

		String key = String.format("%04X", shortAddress);

		ParserLocker lock = new ParserLocker(TypeMessage.READ_IEEE_ADDRESS, key);

		sendFrame(lock, frame);

		return (BigInteger) waitResponse(lock, opcode, timeout);
	}

	public NodeDescriptor getNodeDescriptorSync(long timeout, Address addrOfInterest)
			throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZDPNodeDescriptorRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addShort(addrOfInterest.getNetworkAddress().shortValue());
		frame.addShort(addrOfInterest.getNetworkAddress().shortValue());
		frame.addSequenceStartAndFSC(opcode);

		String key = String.format("%04X", addrOfInterest.getNetworkAddress());

		ParserLocker lock = new ParserLocker(TypeMessage.NODE_DESCRIPTOR, key);

		sendFrame(lock, frame);

		return (NodeDescriptor) waitResponse(lock, opcode, timeout);
	}

	public List<Short> startServiceDiscoverySync(long timeout, Address addrOfInterest) throws Exception {

		short opcode = FreescaleConstants.ZDPActiveEpRequest;

		short shortAddress = addrOfInterest.getNetworkAddress().shortValue();

		LOG.debug("startServiceDiscoverySync Timeout: {}", timeout);

		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addShort(shortAddress);
		frame.addShort(shortAddress);

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.ACTIVE_EP, String.format("%04X", addrOfInterest.getNetworkAddress()));

		sendFrame(lock, frame);

		return (List<Short>) waitResponse(lock, opcode, timeout);
	}

	public Status leaveSync(long timeout, Address addrOfInterest, int mask) throws Exception {

		short opcode = FreescaleConstants.ZDPMgmtLeaveRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addShort(addrOfInterest.getNetworkAddress().shortValue());
		byte[] deviceAddress = DataManipulation.toByteVect(getGal().get_GalNode().get_node().getAddress().getNetworkAddress(), 8);

		frame.addBytes(deviceAddress);

		byte options = 0;
		options = (byte) (options & GatewayConstants.LEAVE_REJOIN);
		options = (byte) (options & GatewayConstants.LEAVE_REMOVE_CHILDERN);
		frame.addByte(options);

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug("Leave command: {}", frame.toString());

		sendFrame(frame);

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

		frame.addByte(endpoint);/* EndPoint */
		frame.addSequenceStartAndFSC(opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.DEREGISTER_END_POINT);

		sendFrame(lock, frame);

		return waitStatus(lock, opcode, timeout);
	}

	public NodeServices getLocalServices(long timeout) throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.APSGetEndPointIdListRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addSequenceStartAndFSC(opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.GET_END_POINT_LIST);

		LOG.debug(frame.toString());

		sendFrame(lock, frame);

		return (NodeServices) waitResponse(lock, opcode, timeout);
	}

	public ServiceDescriptor getServiceDescriptor(long timeout, Address addrOfInterest, short endpoint)
			throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZDPSimpleDescriptorRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addShort(addrOfInterest.getNetworkAddress().shortValue());
		frame.addShort(addrOfInterest.getNetworkAddress().shortValue());
		frame.addByte((byte) endpoint);

		frame.addSequenceStartAndFSC(opcode);

		String key = String.format("%04X", addrOfInterest.getNetworkAddress()) + String.format("%02X", endpoint);

		LOG.debug("ZDP-SimpleDescriptor.Request command: {}", frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.GET_SIMPLE_DESCRIPTOR, key);

		sendFrame(lock, frame);

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

		frame.addSequenceStartAndFSC(FreescaleConstants.ZTCCPUResetRequest);
		LOG.debug("CPUResetCommnad command: {}", frame.toString());
		sendFrame(frame);
	}

	public BindingList getNodeBindings(long timeout, Address addrOfInterest, short index)
			throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZDPMgmtBindRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addShort(addrOfInterest.getNetworkAddress().shortValue());
		frame.addByte((byte) index);

		frame.addSequenceStartAndFSC(opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.GET_BINDINGS);

		sendFrame(lock, frame);

		return (BindingList) waitResponse(lock, opcode, timeout);
	}

	public Status addBinding(long timeout, Binding binding, Address aoi) throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZDPBindRequest;

		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addShort(aoi.getNetworkAddress().shortValue());

		byte[] ieeeAddress = DataManipulation.toByteVect(binding.getSourceIEEEAddress(), 8);

		frame.addBytes(ieeeAddress);
		frame.addByte((byte) binding.getSourceEndpoint());

		int clusterId = binding.getClusterID();

		frame.addShort((short) clusterId);

		if (binding.getDeviceDestination().size() > 0 && binding.getGroupDestination().size() > 0) {
			throw new GatewayException("The Address mode can only be one between Group or Device!");
		} else if (binding.getDeviceDestination().size() == 1) {
			/*
			 * Destination AddressMode IeeeAddress + EndPoint
			 */
			frame.addByte((byte) 0x03);

			byte[] _DestinationieeeAddress = DataManipulation.toByteVect(binding.getDeviceDestination().get(0).getAddress(), 8);
			frame.addBytes(_DestinationieeeAddress);

			/*
			 * Destination EndPoint
			 */
			frame.addByte((byte) binding.getDeviceDestination().get(0).getEndpoint());
		} else if (binding.getGroupDestination().size() == 1) {

			frame.addByte((byte) 0x01); /* Destination AddressMode Group */

			byte[] _DestinationGroupAddress = DataManipulation.toByteVect(binding.getGroupDestination().get(0).longValue(), 8);
			frame.addBytes(_DestinationGroupAddress);

		} else {
			throw new GatewayException("The Address mode can only be one Group or one Device!");
		}

		frame.addSequenceStartAndFSC(opcode);

		frame.addSequenceStartAndFSC(opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.ADD_BINDING);

		sendFrame(lock, frame);

		Status status = waitStatus(lock, opcode, timeout);

		switch (status.getCode()) {
		case GatewayConstants.SUCCESS:
			break;

		case 0x84:
			status.setMessage("NOT_SUPPORTED (NOT SUPPORTED)");
			break;

		case 0x8C:
			status.setMessage("TABLE_FULL (TABLE FULL)");
			break;
		case 0x8D:
			status.setMessage("NOT_AUTHORIZED (NOT AUTHORIZED)");
			break;
		}

		return status;
	}

	public Status removeBinding(long timeout, Binding binding, Address aoi) throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZDPUnbindRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addShort(aoi.getNetworkAddress().shortValue());

		byte[] ieeeAddress = DataManipulation.toByteVect(binding.getSourceIEEEAddress(), 8);
		frame.addBytes(ieeeAddress);
		frame.addByte((byte) binding.getSourceEndpoint()); /* Source EndPoint */

		/* ClusterID */
		Integer _clusterID = binding.getClusterID();
		frame.addShort(_clusterID.shortValue());

		if (binding.getDeviceDestination().size() > 0 && binding.getGroupDestination().size() > 0) {
			throw new GatewayException("The Address mode can only be one between Group or Device!");
		} else if (binding.getDeviceDestination().size() == 1) {
			/*
			 * Destination AddressMode IeeeAddress + EndPoint
			 */
			frame.addByte((byte) 0x03);

			byte[] _DestinationieeeAddress = DataManipulation.toByteVect(binding.getDeviceDestination().get(0).getAddress(), 8);
			frame.addBytes(_DestinationieeeAddress);

			/*
			 * Destination EndPoint
			 */
			frame.addByte((byte) binding.getDeviceDestination().get(0).getEndpoint());

		} else if (binding.getGroupDestination().size() == 1) {
			frame.addByte((byte) 0x01);/* Destination AddressMode Group */

			byte[] _DestinationGroupAddress = DataManipulation.toByteVect(binding.getGroupDestination().get(0).longValue(), 8);

			frame.addBytes(_DestinationGroupAddress);
		} else {
			throw new GatewayException("The Address mode can only be one Group or one Device!");

		}

		frame.addSequenceStartAndFSC(opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.REMOVE_BINDING);

		sendFrame(lock, frame);

		return waitStatus(lock, opcode, timeout);
	}

	public Status frequencyAgilitySync(long timeout, short scanChannel, short scanDuration)
			throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.NLMENWKUpdateReq;

		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addByte((byte) 0xFD);
		frame.addByte((byte) 0xFF);

		byte[] _channel = Utils.buildChannelMask(scanChannel);
		frame.addBytes(_channel);
		frame.addByte((byte) scanDuration);

		/* Add parameter nwkupdate */
		frame.addByte((byte) 0x00);

		// _bodyCommand.addByte((byte) 0x01);// Add parameter nwkupdate
		frame.addSequenceStartAndFSC(opcode);

		sendFrame(frame);

		Status status = new Status();
		status.setCode((short) GatewayConstants.SUCCESS);

		return status;
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

		short opcode = FreescaleConstants.NLMEClearDeviceKeyPairSet;

		ByteArrayObject frame = new ByteArrayObject(false);

		byte[] ieeeAddress = DataManipulation.toByteVect(addrOfInterest.getIeeeAddress(), 8);
		frame.addBytes(ieeeAddress);

		frame.addSequenceStartAndFSC(opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.CLEAR_DEVICE_KEY_PAIR_SET);

		LOG.debug("APS-ClearDeviceKeyPairSet.Request command: {}", frame.toString());

		sendFrame(lock, frame);

		return waitStatus(lock, opcode, timeout);
	}

	public Status ClearNeighborTableEntry(long timeout, Address addrOfInterest) throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.NLMEClearNeighborTableEntry;

		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addByte((byte) 0xFF);
		frame.addByte((byte) 0xFF);

		byte[] ieeeAddress = DataManipulation.toByteVect(addrOfInterest.getIeeeAddress(), 8);
		frame.addBytes(ieeeAddress);

		frame.addSequenceStartAndFSC(opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.CLEAR_NEIGHBOR_TABLE_ENTRY);

		LOG.debug(frame.toString());

		sendFrame(lock, frame);

		return waitStatus(lock, opcode, timeout);
	}

	public Status NMLE_SETSync(long timeout, short _AttID, String _value) throws Exception {

		short opcode = FreescaleConstants.NLMESetRequest;

		ByteArrayObject frame = new ByteArrayObject(false);
		frame.addByte((byte) _AttID);/* _AttId */
		frame.addByte((byte) 0x00);
		frame.addByte((byte) 0x00);
		frame.addByte((byte) 0x00);
		for (byte x : DataManipulation.hexStringToByteArray(_value)) {
			frame.addByte(x);
		}

		frame.addSequenceStartAndFSC(opcode);

		ParserLocker lock = new ParserLocker(TypeMessage.NMLE_SET);

		sendFrame(lock, frame);

		return waitStatus(lock, opcode, timeout);
	}

	public Mgmt_LQI_rsp Mgmt_Lqi_Request(long timeout, Address addrOfInterest, short startIndex)
			throws IOException, Exception, GatewayException {

		short opcode = FreescaleConstants.ZDPMgmtLqiRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addShort(addrOfInterest.getNetworkAddress().shortValue());
		frame.addByte((byte) startIndex);

		frame.addSequenceStartAndFSC(opcode);

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

		sendFrame(lock, frame);

		return (Mgmt_LQI_rsp) waitResponse(lock, opcode, timeout);
	}

	public Status sendInterPANMessaSync(long timeout, InterPANMessage message) throws Exception {

		short opcode = FreescaleConstants.InterPANDataRequest;

		ByteArrayObject frame = new ByteArrayObject(false);
		byte sam = (byte) message.getSrcAddressMode();
		frame.addByte(sam);

		byte dam = (byte) message.getDstAddressMode();
		frame.addByte(dam);

		frame.addShort((short) message.getDestPANID());

		Address dstaddress = message.getDestinationAddress();

		switch (dam) {

		case GatewayConstants.ADDRESS_MODE_SHORT:
			byte[] networkAddress = DataManipulation.toByteVect(dstaddress.getNetworkAddress(), 8);
			frame.addBytes(networkAddress);
			break;

		case GatewayConstants.EXTENDED_ADDRESS_MODE:
			byte[] ieeeAddress = DataManipulation.toByteVect(dstaddress.getIeeeAddress(), 8);
			frame.addBytes(ieeeAddress);
			break;

		case GatewayConstants.ADDRESS_MODE_ALIAS:
			// TODO Control those address modes!
			throw new UnsupportedOperationException("Address Mode Alias");

		default:
			throw new Exception("Address Mode undefined!");
		}

		frame.addShort(message.getProfileID().shortValue());
		frame.addShort((short) message.getClusterID());

		if (message.getASDULength() > 0x64) {
			throw new Exception("ASDU length must 0x64 or less in length");
		} else {
			frame.addByte((byte) message.getASDULength());

		}

		for (Byte b : message.getASDU()) {
			frame.addByte(b);
		}

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug("Write InterPanMessage on: {} Message: {}", System.currentTimeMillis(), frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.INTERPAN);

		sendFrame(lock, frame);

		return waitStatus(lock, opcode, timeout);
	}

	public boolean getDestroy() {
		synchronized (destroy) {
			return destroy;
		}
	}

	public String MacGetPIBAttributeSync(long timeout, short _AttID) throws Exception {

		short opcode = FreescaleConstants.MacGetPIBAttributeRequest;

		ByteArrayObject frame = new ByteArrayObject(false);

		frame.addByte((byte) _AttID); /* iId */
		frame.addByte((byte) 0x00); /* iIndex */

		frame.addSequenceStartAndFSC(opcode);

		LOG.debug(frame.toString());

		ParserLocker lock = new ParserLocker(TypeMessage.MAC_GET, String.format("%02X", _AttID));

		sendFrame(lock, frame);

		return (String) waitResponse(lock, opcode, timeout);
	}

	/**
	 * Wait till the passed ParserLocker object is satisfied.
	 * 
	 * @param lock
	 *          A ParserLocker instance. It has to be constructed by specifiying
	 *          the information to wait for from the response.
	 * @param opcode
	 *          The opcode of the issued command. This is useful for logging
	 *          purposes.
	 * @param timeout
	 *          The timeout.
	 * @return The returned object.
	 * 
	 * @throws GatewayException
	 *           In case of errors
	 * 
	 * @throws InterruptedException
	 */
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

	private void fireLocker(TypeMessage type, Object result, short status) {
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == type) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)) {

					pl.set_objectOfResponse(result);
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

	private void fireLocker(TypeMessage type, String key, Object result, short status) {
		synchronized (getListLocker()) {
			for (ParserLocker pl : getListLocker()) {
				if ((pl.getType() == type) && (pl.getStatus().getCode() == ParserLocker.INVALID_ID)
						&& (pl.get_Key().equalsIgnoreCase(key))) {

					pl.set_objectOfResponse(result);
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
}
