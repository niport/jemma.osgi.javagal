package org.energy_home.jemma.javagal.layers.data.implementations.IDataLayerImplementation;

import java.util.concurrent.LinkedBlockingDeque;

import org.energy_home.jemma.javagal.layers.data.implementations.SerialPortConnectorJssc;
import org.energy_home.jemma.javagal.layers.data.interfaces.IConnector;
import org.energy_home.jemma.javagal.layers.object.ByteArrayObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RS232Filter implements Runnable {
	
	private static int SIZE = 1024;
	private static int DELAY = 200;
	private static RS232Filter instance = null;
	private LinkedBlockingDeque<ByteArrayObject> queue;
	private IConnector dongleRs232;
		
	private static final Logger LOG = LoggerFactory.getLogger(SerialPortConnectorJssc.class);
	
	public static RS232Filter create(IConnector dongleRs232)
	{
		if(instance==null)
			instance = new RS232Filter(dongleRs232);
		return instance;
	}
	
	public static RS232Filter getInstance(IConnector dongleRs232)
	{
		// This is because the dongleRs232 may change due to a dongle reset ...
		instance.dongleRs232 = dongleRs232;
		return instance;
	}
	
	private RS232Filter(IConnector dongleRs232)
	{
		this.dongleRs232 = dongleRs232;
		queue = new LinkedBlockingDeque<ByteArrayObject>(SIZE);
		
		// Main thread ...
		new Thread(this).start();
	}
	
	public void write(ByteArrayObject command)
	{
		try {
			// Put a command in the tail ...
			queue.put(command);
			LOG.debug("RS232Filter - Got a command: " + command.ToHexString());
		} catch (InterruptedException e) {
			System.out.println("RS232Filter - Error queueing command: " + command.ToHexString());
			e.printStackTrace();
		}
	}

	public void run() 
	{		
		while(true)
		{
			try {
				Thread.sleep(DELAY);
				
				// Take a command from the head ...
				ByteArrayObject command = queue.take();
				
				synchronized(dongleRs232)
				{
					if(dongleRs232.isConnected())
					{
						// Write down the command on the serial port ...
						LOG.debug("RS232Filter - Sending command: " + command.ToHexString());
						dongleRs232.write(command);
					}
					else
					{
						// Write down the command on the serial port ...
						LOG.warn("RS232Filter - Dongle disconnected, outgoing command: " + command.ToHexString());
						
						// Re-insert the command in the head of the queue ..
						queue.addFirst(command);
					}
				}

			} catch (Exception e) {
				LOG.error("RS232Filter - Error in the main loop");
				e.printStackTrace();
			}
		}
	}

	public IConnector getDongleRs232() 
	{
		return dongleRs232;
	}

	public void setDongleRs232(IConnector dongleRs232) 
	{
		this.dongleRs232 = dongleRs232;
	}
}
