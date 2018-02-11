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
package org.energy_home.jemma.javagal.layers.presentation;

import org.energy_home.jemma.javagal.layers.PropertiesManager;
import org.energy_home.jemma.javagal.layers.business.GalController;
import org.energy_home.jemma.javagal.layers.object.GatewayProperties;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Osgi Activator implementation.
 * 
 * @author "Ing. Marco Nieddu
 *         <a href="mailto:marco.nieddu@consoft.it ">marco.nieddu@consoft.it</a>
 *         or <a href="marco.niedducv@gmail.com ">marco.niedducv@gmail.com</a>
 *         from Consoft Sistemi S.P.A.<http://www.consoft.it>, financed by EIT
 *         ICT Labs activity SecSES - Secure Energy Systems (activity id 13030)"
 */
public class Activator implements BundleActivator {
	private BundleContext bc;

	private static final Logger LOG = LoggerFactory.getLogger("Javagal");

	private ServiceRegistration<?> galControllerReg;

	private GalController galController;

	public void start(BundleContext bc) throws Exception {
		LOG.info("Starting Gal:Osgi...");
		this.bc = bc;
		try {
			String _path = "config.properties";

			LOG.info("FILE Conf: " + _path);

			PropertiesManager configuration = new PropertiesManager(bc.getBundle().getResource(_path));

			if (bc.getProperty(GatewayProperties.ZGD_DONGLE_URI_PROP_NAME) != null)
				configuration.props.setProperty(GatewayProperties.ZGD_DONGLE_URI_PROP_NAME,
						bc.getProperty(GatewayProperties.ZGD_DONGLE_URI_PROP_NAME));
			if (bc.getProperty(GatewayProperties.ZGD_DONGLE_SPEED_PROP_NAME) != null)
				configuration.props.setProperty(GatewayProperties.ZGD_DONGLE_SPEED_PROP_NAME,
						bc.getProperty(GatewayProperties.ZGD_DONGLE_SPEED_PROP_NAME));
			if (bc.getProperty(GatewayProperties.ZGD_DONGLE_TYPE_PROP_NAME) != null)
				configuration.props.setProperty(GatewayProperties.ZGD_DONGLE_TYPE_PROP_NAME,
						bc.getProperty(GatewayProperties.ZGD_DONGLE_TYPE_PROP_NAME));
			if (bc.getProperty(GatewayProperties.ZGD_GAL_ENABLE_LOG) != null)
				configuration.props.setProperty("debugEnabled", bc.getProperty(GatewayProperties.ZGD_GAL_ENABLE_LOG));
			if (bc.getProperty(GatewayProperties.ZGD_GAL_ENABLE_SERIAL_LOG) != null)
				configuration.props.setProperty("serialDataDebugEnabled", bc.getProperty(GatewayProperties.ZGD_GAL_ENABLE_SERIAL_LOG));

			this.galController = new GalController(configuration);

			this.galController.activate();

			galControllerReg = bc.registerService(GalController.class.getName(), this.galController, null);

			LOG.info("Gal:Osgi Started!");
		} catch (Exception e) {
			LOG.error("Error Creating Gal Osgi", e);
		}
	}

	public void stop(BundleContext bundleContext) throws Exception {

		if (galController != null) {
			this.galControllerReg.unregister();
			this.galController.deactivate();
		}
		LOG.info("Gal Osgi Stopped!");
	}
}
