package org.zonca.zproxy;



public class MainClass {

	public static void main(String[] args) {
		if (args.length != 3) {
			System.err
					.println("Required parameters : <local port> <remote host> <remote port>");
			return;
		}
		
		int localPort = Integer.parseInt(args[0]);
		String remoteHost = args[1];
		int remotePort = Integer.parseInt(args[2]);
		
		new ZProxy(localPort, remoteHost, remotePort).run();
	}
}
