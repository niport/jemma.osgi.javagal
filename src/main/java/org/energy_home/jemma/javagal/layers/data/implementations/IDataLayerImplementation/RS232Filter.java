package org.energy_home.jemma.javagal.layers.data.implementations.IDataLayerImplementation;

import java.util.concurrent.LinkedBlockingDeque;

import org.energy_home.jemma.javagal.layers.data.implementations.SerialPortConnectorJssc;
import org.energy_home.jemma.javagal.layers.data.interfaces.IConnector;
import org.energy_home.jemma.javagal.layers.object.ByteArrayObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RS232Filter implements Runnable {

	private static int SIZE = 1024; // Queue maximum size ...
	private static int DELAY = 200; // Delay between commands ...
	private static RS232Filter instance = null;
	private LinkedBlockingDeque<ByteArrayObject> queue;
	private IConnector dongleRs232;
	private boolean running = true;
	private Thread innerThread = null;

	private static final Logger LOG = LoggerFactory.getLogger(SerialPortConnectorJssc.class);

	public synchronized static RS232Filter create(IConnector dongleRs232) {
		if (instance == null) {
			LOG.debug("Creating instance ...");
			instance = new RS232Filter(dongleRs232);
		} else
			LOG.warn("Trying to create a second instance!");

		return instance;
	}

	public static void destroy() {
		LOG.debug("Destroying instance ...");

		if (instance != null) {
			instance.running = false;

			try {
				// Unblocks the queue for the last time, in case
				// it was blocked on a take request ...
				instance.queue.put(new ByteArrayObject(true));
			} catch (InterruptedException e) {
				LOG.error("Error unblocking the queue");
				e.printStackTrace();
			}

			try {
				// Wait for the main thread to be over ...
				if (instance.innerThread != null)
					instance.innerThread.join();
			} catch (InterruptedException e) {
				LOG.error("Error waiting for the main thread to be over");
				e.printStackTrace();
			}

			instance = null;

			LOG.debug("*** Correctly destroyed!");
		}
	}

	public static RS232Filter getInstance() {
		return instance;
	}

	private RS232Filter(IConnector dongleRs232) {
		this.dongleRs232 = dongleRs232;
		queue = new LinkedBlockingDeque<ByteArrayObject>(SIZE);

		// Main thread ...
		(innerThread = new Thread(this)).start();
	}

	public void write(ByteArrayObject command) {
		try {
			// Put a command in the tail ...
			queue.put(command);
			LOG.debug("Got a command: " + command.ToHexString());
		} catch (InterruptedException e) {
			System.out.println("Error queueing command: " + command.ToHexString());
			e.printStackTrace();
		}
	}

	public void run() {
		while (running) {
			try {
				Thread.sleep(DELAY);

				// Take a command from the head ...
				ByteArrayObject command = queue.take();

				synchronized (dongleRs232) {
					if (running) {
						if (dongleRs232.isConnected()) {
							// Write down the command on the serial port ...
							LOG.debug("Sending command: " + command.ToHexString());
							dongleRs232.write(command);
						} else {
							// Write down the command on the serial port ...
							LOG.warn("Dongle disconnected, outgoing command: " + command.ToHexString());

							// Re-insert the command in the head of the queue ..
							queue.addFirst(command);
						}
					}
				}

			} catch (Exception e) {
				LOG.error("Error in the main loop");
				e.printStackTrace();
			}
		}

		LOG.warn("*** Main thread terminated ...");
	}

	public IConnector getDongleRs232() {
		return dongleRs232;
	}

	public void setDongleRs232(IConnector dongleRs232) {
		this.dongleRs232 = dongleRs232;
	}
}
