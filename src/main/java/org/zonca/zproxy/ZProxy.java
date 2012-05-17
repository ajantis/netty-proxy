package org.zonca.zproxy;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

public class ZProxy {

	private final int localPort;
	private final String remoteHost;
	private final int remotePort;

	public ZProxy(int localPort, String remoteHost, int remotePort) {
		this.localPort = localPort;
		this.remoteHost = remoteHost;
		this.remotePort = remotePort;
	}

	public void run() {
		System.err.println("Proxying *:" + localPort + " to " + remoteHost
				+ ':' + remotePort + " ...");

		// Configure the bootstrap.
		Executor executor = Executors.newCachedThreadPool();
		ServerBootstrap sb = new ServerBootstrap(
				new NioServerSocketChannelFactory(executor, executor));

		// Set up the event pipeline factory.
		ClientSocketChannelFactory cf = new NioClientSocketChannelFactory(
				executor, executor);

		sb.setPipelineFactory(new ProxyPipelineFactory(cf, remoteHost,
				remotePort));

		// Start up the server.
		sb.bind(new InetSocketAddress(localPort));

	}

}
