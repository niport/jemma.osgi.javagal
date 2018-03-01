package org.energy_home.jemma.javagal.layers.data.implementations.Utils;

public class LogUtils {

	public String decodePermitJoinStatus(short status) {
		String s = "";

		switch (status) {
		case 0x00:
			break;

		case 0x80:
			s = "InvRequestType";
			break;

		case 0x84:
			s = "Not Supported";
			break;

		case 0x87:
			s = "Table Full";
			break;

		case 0x8D:
			s = "NOT AUTHORIZED";
			break;

		case 0xC5:
			s = "Already present in the network";
			break;

		default:
			s = "Unknown Status: " + status;
		}
		return s;
	}

	public String decodeUnbindResponseStatus(short status) {
		switch (status) {
		case 0x00:
			return "SUCCESS";

		case 0x84:
			return "NOT_SUPPORTED (NOT SUPPORTED)";

		case 0x88:
			return "No_Entry (No Entry)";

		case 0x8D:
			return "NOT_AUTHORIZED (NOT AUTHORIZED";
		}

		return "Unknown Status: " + status;
	}

	public static String decodeApsdeDataConfirmStatus(short status) {

		switch (status) {
		case 0x00:
			return "gSuccess (Success)";

		case 0x05:
			return "gPartialSuccess (Partial Success)";

		case 0x07:
			return "gSecurity_Fail (Security fail)";

		case 0x0A:
			return "gApsInvalidParameter_c (Security fail)";

		case 0x04:
			return "gZbNotOnNetwork_c (Transmitted the data frame)";

		case 0x01:
			return "gApsIllegalDevice_c (Transmitted the data frame)";

		case 0x02:
			return "gZbNoMem_c (Transmitted the data frame)";

		case 0xA0:
			return "gApsAsduTooLong_c (ASDU too long)";

		case 0xA3:
			return "gApsIllegalRequest_c (Invalid parameter)";

		case 0xA8:
			return "gNo_BoundDevice (No bound device)";

		case 0xA9:
			return "gNo_ShortAddress (No Short Address)";

		case 0xAE:
			return "gApsTableFull_c (Aps Table Full)";

		case 0xC3:
			return "INVALID_REQUEST (Not a valid request)";

		case 0xCC:
			return "MAX_FRM_COUNTER (Frame counter has reached maximum value for outgoing frame)";

		case 0xCD:
			return "NO_KEY (Key not available)";

		case 0xCE:
			return "BAD_CCM_OUTPUT (Security engine produced erraneous output)";

		case 0xF1:
			return "TRANSACTION_OVERFLOW (Transaction Overflow)";

		case 0xF0:
			return "TRANSACTION_EXPIRED (Transaction Expired)";

		case 0xE1:
			return " CHANNEL_ACCESS_FAILURE (Key not available)";

		case 0xE6:
			return "INVALID_GTS (Not valid GTS)";

		case 0xF3:
			return "UNAVAILABLE_KEY (Key not found)";

		case 0xE5:
			return "FRAME_TOO_LONG (Frame too long)";

		case 0xE4:
			return "FAILED_SECURITY_CHECK (Failed security check)";

		case 0xE8:
			return "INVALID_PARAMETER (Not valid parameter)";

		case 0xE9:
			return "NO_ACK (Acknowledgement was not received)";

		default:
			return "Unknown APSDE Status " + status;
		}

	}

	public static String decodeNetworkStatusEvent(short status) {
		switch (status) {
		case 0x00:
			return "DeviceInitialized (Device Initialized)";

		case 0x01:
			return "DeviceinNetworkDiscoveryState (Device in Network Discovery State)";

		case 0x02:
			return "DeviceJoinNetworkstate (Device Join Network state)";

		case 0x03:
			return "DeviceinCoordinatorstartingstate (Device in Coordinator starting state)";

		case 0x04:
			return "DeviceinRouterRunningstate (Device in Router Running state)";

		case 0x05:
			return "DeviceinEndDeviceRunningstate (Device in End Device Running state)";

		case 0x09:
			return "Deviceinleavenetworkstate (Device in leave network state)";

		case 0x0A:
			return "Deviceinauthenticationstate (Device in authentication state)";

		case 0x0B:
			return "Deviceinstoppedstate (Device in stopped state)";

		case 0x0C:
			return "DeviceinOrphanjoinstate (Device in Orphan join state)";

		case 0x10:
			return "DeviceinCoordinatorRunningstate (Device is Coordinator Running state)";

		case 0x11:
			return "DeviceinKeytransferstate (Device in Key transfer state)";

		case 0x12:
			return "Deviceinauthenticationstate (Device in authentication state)";

		case 0x13:
			return "DeviceOfftheNetwork (Device Off the Network)";

		default:
			return "Invalid Status - " + status;
		}
	}

	public static String ztcErrorStatus(short status) {

		switch (status) {

		case 0x00:
			return "0x00: gSuccess_c (Should not be seen in this event.";

		case 0xF4:
			return "0xF4: gZtcOutOfMessages_c (ZTC tried to allocate a message, but the allocation failed.";

		case 0xF5:
			return "0xF5: gZtcEndPointTableIsFull_c (Self explanatory.";

		case 0xF6:
			return "0xF6: gZtcEndPointNotFound_c (Self explanatory.";

		case 0xF7:
			return "0xF7: gZtcUnknownOpcodeGroup_c (ZTC does not recognize the opcode group, and there is no application hook.";

		case 0xF8:
			return "0xF8: gZtcOpcodeGroupIsDisabled_c (ZTC support for an opcode group is turned off by a compile option.";

		case 0xF9:
			return "0xF9: gZtcDebugPrintFailed_c (An attempt to print a debug message ran out of buffer space.";

		case 0xFA:
			return "0xFA: gZtcReadOnly_c (Attempt to set read-only data.";

		case 0xFB:
			return "0xFB: gZtcUnknownIBIdentifier_c (Self explanatory.";

		case 0xFC:
			return "0xFC: gZtcRequestIsDisabled_c (ZTC support for an opcode is turned off by a compile option.";

		case 0xFD:
			return "0xFD: gZtcUnknownOpcode_c (Self expanatory.";

		case 0xFE:
			return "0xFE: gZtcTooBig_c (A data item to be set or retrieved is too big for the buffer available to hold it.";

		case 0xFF:
			return "0xFF: gZtcError_c (Non-specific, catchall error code.";

		default:
			return status + ": unknown ZTC error status.";
		}
	}

	public static String nlmeJoinConfirmStatus(short status) {
		switch (status) {
		case 0x00:
			return "SUCCESS (Joined the network)";

		case 0xC2:
			return "INVALID_REQUEST (Not Valid Request)";

		case 0xC3:
			return "NOT_PERMITTED (Not allowed to join the network)";

		case 0xCA:
			return "NO_NETWORKS (Network not found)";

		case 0x01:
			return "PAN_AT_CAPACITY (PAN at capacity)";

		case 0x02:
			return "PAN_ACCESS_DENIED (PAN access denied)";

		case 0xE1:
			return "CHANNEL_ACCESS_FAILURE (Transmission failed due to activity on the channel)";

		case 0xE4:
			return "FAILED_SECURITY_CHECK (The received frame failed security check)";

		case 0xE8:
			return "INVALID_PARAMETER (A parameter in the primitive is out of the valid range)";

		case 0xE9:
			return "NO_ACK (Acknowledgement was not received)";

		case 0xEB:
			return "NO_DATA (No response data was available following a request)";

		case 0xF3:
			return "UNAVAILABLE_KEY (The appropriate key is not available in the ACL)";

		case 0xEA:
			return "NO_BEACON (No Networks)";

		default:
			return "Invalid Status - " + status;
		}
	}
}
