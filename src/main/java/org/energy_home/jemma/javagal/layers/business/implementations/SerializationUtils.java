package org.energy_home.jemma.javagal.layers.business.implementations;

import org.energy_home.jemma.zgd.jaxb.APSMessageEvent;
import org.energy_home.jemma.zgd.jaxb.Address;
import org.energy_home.jemma.zgd.jaxb.Aliases;
import org.energy_home.jemma.zgd.jaxb.BindingList;
import org.energy_home.jemma.zgd.jaxb.CallbackIdentifierList;
import org.energy_home.jemma.zgd.jaxb.InterPANMessageEvent;
import org.energy_home.jemma.zgd.jaxb.LQIInformation;
import org.energy_home.jemma.zgd.jaxb.NodeDescriptor;
import org.energy_home.jemma.zgd.jaxb.NodeServices;
import org.energy_home.jemma.zgd.jaxb.NodeServicesList;
import org.energy_home.jemma.zgd.jaxb.ServiceDescriptor;
import org.energy_home.jemma.zgd.jaxb.Status;
import org.energy_home.jemma.zgd.jaxb.Version;
import org.energy_home.jemma.zgd.jaxb.WSNNode;
import org.energy_home.jemma.zgd.jaxb.WSNNodeList;
import org.energy_home.jemma.zgd.jaxb.ZCLMessage;
import org.energy_home.jemma.zgd.jaxb.ZDPMessage;

/**
 * This class is a replacement of the lang3 library. This library has a size of
 * about 400KB.
 * 
 * For certain classes we simply return them without cloning.
 */
public class SerializationUtils {

	public static Status clone(Status status) {

		Status clone = new Status();

		clone.setCode(status.getCode());
		clone.setMessage(status.getMessage());

		return clone;
	}

	public static NodeServices clone(NodeServices nodeServices) {
		return nodeServices;
	}

	public static ServiceDescriptor clone(ServiceDescriptor serviceDescriptor) {
		return serviceDescriptor;
	}

	public static Aliases clone(Aliases aliases) {
		return aliases;
	}

	public static NodeServicesList clone(NodeServicesList nodeServicesList) {
		return nodeServicesList;
	}

	public static NodeDescriptor clone(NodeDescriptor nodeDescriptor) {
		return nodeDescriptor;
	}

	public static LQIInformation clone(LQIInformation lqiInformation) {
		return lqiInformation;
	}

	public static WSNNodeList clone(WSNNodeList wsnNodesList) {
		return wsnNodesList;
	}

	public static CallbackIdentifierList clone(CallbackIdentifierList callbackIdentifiersList) {
		return callbackIdentifiersList;
	}

	public static Version clone(Version version) {
		return version;
	}

	public static BindingList clone(BindingList bindingList) {
		return bindingList;
	}

	public static APSMessageEvent clone(APSMessageEvent message) {
		return message;
	}

	public static Address clone(Address address) {
		Address addressCopy = new Address();
		addressCopy.setIeeeAddress(address.getIeeeAddress());
		addressCopy.setNetworkAddress(address.getNetworkAddress());
		return address;
	}

	public static WSNNode clone(WSNNode node) {
		return node;
	}

	public static ZDPMessage clone(ZDPMessage message) {
		return message;
	}

	public static ZCLMessage clone(ZCLMessage message) {
		return message;
	}

	public static InterPANMessageEvent clone(InterPANMessageEvent message) {
		return message;
	}

}
