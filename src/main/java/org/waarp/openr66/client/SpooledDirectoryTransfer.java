/**
 * This file is part of Waarp Project.
 * 
 * Copyright 2009, Frederic Bregier, and individual contributors by the @author tags. See the
 * COPYRIGHT.txt in the distribution for a full listing of individual contributors.
 * 
 * All Waarp Project is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * Waarp is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Waarp . If not, see
 * <http://www.gnu.org/licenses/>.
 */
package org.waarp.openr66.client;

import java.io.File;
import java.io.FileFilter;

import org.jboss.netty.logging.InternalLoggerFactory;
import org.waarp.common.database.exception.WaarpDatabaseException;
import org.waarp.common.filemonitor.FileMonitor;
import org.waarp.common.filemonitor.FileMonitorCommand;
import org.waarp.common.filemonitor.RegexFileFilter;
import org.waarp.common.logging.WaarpInternalLogger;
import org.waarp.common.logging.WaarpInternalLoggerFactory;
import org.waarp.common.logging.WaarpSlf4JLoggerFactory;
import org.waarp.openr66.configuration.FileBasedConfiguration;
import org.waarp.openr66.context.ErrorCode;
import org.waarp.openr66.context.R66Result;
import org.waarp.openr66.database.DbConstant;
import org.waarp.openr66.database.data.DbRule;
import org.waarp.openr66.database.data.DbTaskRunner;
import org.waarp.openr66.protocol.configuration.Configuration;
import org.waarp.openr66.protocol.networkhandler.NetworkTransaction;
import org.waarp.openr66.protocol.utils.ChannelUtils;
import org.waarp.openr66.protocol.utils.R66Future;

/**
 * Direct Transfer from a client with or without database connection 
 * or Submit Transfer from a client with database connection 
 * to transfer files from a spooled directory to possibly multiple hosts at once.<br>
 * Hosts will have to be separated by ','.<br>
 * <br>
 * Mandatory additional elements:<br>
 * -directory source (directory to spooled on)<br>
 * -statusfile file (file to use as permanent status (if process is killed or aborts))<br>
 * -stopfile file (file when created will stop the dameon)<br>
 * Other options:<br>
 * -regex regex (regular expression to filter file names from directory source)<br>
 * -elapse elapse (elapse time between 2 checks of the directory)<br>
 * -submit (to submit only: default)<br>
 * -direct (to directly transfer only)<br>
 * 
 * @author Frederic Bregier
 * 
 */
public class SpooledDirectoryTransfer implements Runnable {
	/**
	 * Internal Logger
	 */
	static protected volatile WaarpInternalLogger logger;
	
	protected static final String NOINFO = "noinfo";

	protected final R66Future future;

	protected final String directory;

	protected final String statusFile;

	protected final String stopFile;

	protected final String rulename;

	protected final String fileinfo;

	protected final boolean isMD5;

	protected final String remoteHosts;

	protected final String regexFilter;

	protected final int blocksize;

	protected final long elapseTime;

	protected final boolean submit;
	
	protected final boolean nolog;
	
	protected final NetworkTransaction networkTransaction;
	
	public long sent = 0;
	public long error = 0;
	
	
	/**
	 * @param future
	 * @param directory
	 * @param statusfile
	 * @param stopfile
	 * @param rulename
	 * @param fileinfo
	 * @param isMD5
	 * @param remoteHosts
	 * @param blocksize
	 * @param regex
	 * @param elapse
	 * @param submit
	 * @param nolog
	 * @param networkTransaction
	 */
	public SpooledDirectoryTransfer(R66Future future, String directory, 
			String statusfile, String stopfile, String rulename,
			String fileinfo, boolean isMD5, 
			String remoteHosts, int blocksize, String regex,
			long elapse, boolean submit, boolean nolog, NetworkTransaction networkTransaction) {
		if (logger == null) {
			logger = WaarpInternalLoggerFactory.getLogger(SpooledDirectoryTransfer.class);
		}
		this.future = future;
		this.directory = directory;
		this.statusFile = statusfile;
		this.stopFile = stopfile;
		this.rulename = rulename;
		this.fileinfo = fileinfo;
		this.isMD5 = isMD5;
		this.remoteHosts = remoteHosts;
		this.blocksize = blocksize;
		this.regexFilter = regex;
		this.elapseTime = elapse;
		this.submit = submit;
		this.nolog = nolog && (!submit);
		AbstractTransfer.nolog = this.nolog;
		this.networkTransaction = networkTransaction;
	}

	@Override
	public void run() {
		sent = 0;
		error  = 0;
		final String [] allrhosts = remoteHosts.split(",");
		// first check if rule is for SEND
		DbRule dbrule = null;
		try {
			dbrule = new DbRule(DbConstant.admin.session, rulename);
		} catch (WaarpDatabaseException e1) {
			logger.error("Rule error: ", e1);
			this.future.setFailure(e1);
			return;
		}
		if (dbrule.isRecvMode()) {
			logger.error("Rule error: not SEND MODE");
			this.future.cancel();
			return;
		}
		File status = new File(statusFile);
		if (status.isDirectory()) {
			logger.error("Status File is a directory but must be a file");
			this.future.cancel();
			return;
		}
		File stop = new File(stopFile);
		if (stop.isDirectory()) {
			logger.error("Stop File is a directory but must be a file");
			this.future.cancel();
			return;
		} else if (stop.exists()) {
			logger.warn("Stop File is found");
			this.future.setSuccess();
			return;
		}
		File dir = new File(directory);
		if (!dir.isDirectory()) {
			logger.error("Directory is not a directory but must be a directory");
			this.future.cancel();
			return;
		}
		FileFilter filter = null;
		if (regexFilter != null) {
			filter = new RegexFileFilter(regexFilter);
		}
		FileMonitorCommand commandValidFile = new FileMonitorCommand() {
			public void run(File file) {
				for (String host : allrhosts) {
					host = host.trim();
					if (host != null && ! host.isEmpty()) {
						String filename = file.getAbsolutePath();
						logger.info("Launch transfer to "+host+" with file "+filename);
						R66Future future = new R66Future(true);
						String text = null;
						if (submit) {
							text = "Submit Transfer";
							SubmitTransfer transaction = new SubmitTransfer(future,
									host, filename, rulename, fileinfo, isMD5, blocksize, 
									DbConstant.ILLEGALVALUE, null);
							transaction.run();
						} else {
							text = "Direct Transfer";
							DirectTransfer transaction = new DirectTransfer(future,
									host, filename, rule, fileInfo, ismd5, block, 
									DbConstant.ILLEGALVALUE, networkTransaction);
							logger.debug("rhost: "+host+":"+transaction.remoteHost);
							transaction.run();
						}
						future.awaitUninterruptibly();
						R66Result result = future.getResult();
						if (future.isSuccess()) {
							sent++;
							DbTaskRunner runner = null;
							if (result != null) {
								runner = result.runner;
								if (runner != null) {
									String status = "SUCCESS";
									if (runner.getErrorInfo() == ErrorCode.Warning) {
										status = "WARNED";
									}
									logger.warn(text+" in status: "+status+"     "
											+ runner.toShortString()
											+"     <REMOTE>"+ host+ "</REMOTE>"
											+"     <FILEFINAL>"+
											(result.file != null ? 
													result.file.toString() + "</FILEFINAL>"
													: "no file"));
									if (nolog && !submit) {
										// In case of success, delete the runner
										try {
											runner.delete();
										} catch (WaarpDatabaseException e) {
											logger.warn("Cannot apply nolog to     " +
													runner.toShortString(),
													e);
										}
									}
								} else {
									logger.warn(text+" in     SUCCESS     " 
											+"<REMOTE>" + host + "</REMOTE>");
								}
							} else {
								logger.warn(text+" in     SUCCESS     " 
										+"<REMOTE>" + host + "</REMOTE>");
							}
						} else {
							error++;
							DbTaskRunner runner = null;
							if (result != null) {
								runner = result.runner;
								if (runner != null) {
									logger.error(text+" in     FAILURE      " + 
											runner.toShortString() +
										"<REMOTE>" + host + "</REMOTE>", future.getCause());
								} else {
									logger.error(text+" in     FAILURE      ", 
											future.getCause());
								}
							} else {
								logger.error(text+" in     FAILURE      "
										+"<REMOTE>" + host + "</REMOTE>", 
										future.getCause());
							}
						}
					}
				}
			}
		};
		FileMonitor monitor = new FileMonitor(status, stop, dir, null, elapsed, filter, 
				commandValidFile, null);
		monitor.start();
		monitor.waitForStopFile();
		this.future.setSuccess();
	}

	
	static protected String rhosts = null;
	static protected String localDirectory = null;
	static protected String rule = null;
	static protected String fileInfo = null;
	static protected boolean ismd5 = false;
	static protected int block = 0x10000; // 64K
											// as
											// default
	static protected String statusfile = null;
	static protected String stopfile = null;
	static protected String regex = null;
	static protected long elapsed = 1000;
	static protected boolean tosubmit = true;
	static protected boolean noLog = false;
	
	/**
	 * Parse the parameter and set current values
	 * 
	 * @param args
	 * @return True if all parameters were found and correct
	 */
	protected static boolean getParams(String[] args) {
		if (args.length < 11) {
			logger
					.error("Needs at least 11 arguments:\n"
							+
							"  the XML client configuration file,\n"
							+
							"  '-to' the remoteHost Id or Ids as a comma separated list,\n"
							+
							"  '-directory' the directory to spool,\n" +
							"  '-statusfile' file (file to use as permanent status (if process is killed or aborts)),\n" +
							"  '-stopfile' file (when this file is created, the daemon stops),\n"
							+
							"  '-rule' the rule\n"
							+
							"Other options:\n"
							+
							"  '-info' \"information to send\",\n"
							+
							"  '-md5' to force MD5 (or other hash as configured) by packet control,\n"
							+
							"  '-block' size of packet > 1K (prefered is 64K),\n"
							+
							"  '-nolog' to not log locally this action,\n" +
							"  '-regex' regex (regular expression to filter file names from directory source),\n" +
							"  '-elapse' elapse (elapse time between 2 checks of the directory),\n" +
							"  '-submit' (to submit only: default),\n" +
							"  '-direct' (to directly transfer only)");
			return false;
		}
		if (!FileBasedConfiguration
				.setClientConfigurationFromXml(Configuration.configuration, args[0])) {
			logger
					.error("Needs a correct configuration file as first argument");
			return false;
		}
		// Now set default values from configuration
		block = Configuration.configuration.BLOCKSIZE;
		int i = 1;
		try {
			for (i = 1; i < args.length; i++) {
				if (args[i].equalsIgnoreCase("-to")) {
					i++;
					rhosts = args[i];
				} else if (args[i].equalsIgnoreCase("-directory")) {
					i++;
					localDirectory = args[i];
				} else if (args[i].equalsIgnoreCase("-rule")) {
					i++;
					rule = args[i];
				} else if (args[i].equalsIgnoreCase("-statusfile")) {
					i++;
					statusfile = args[i];
				} else if (args[i].equalsIgnoreCase("-stopfile")) {
					i++;
					stopfile = args[i];
				} else if (args[i].equalsIgnoreCase("-info")) {
					i++;
					fileInfo = args[i];
				} else if (args[i].equalsIgnoreCase("-md5")) {
					ismd5 = true;
				} else if (args[i].equalsIgnoreCase("-block")) {
					i++;
					block = Integer.parseInt(args[i]);
					if (block < 100) {
						logger.error("Block size is too small: " + block);
						return false;
					}
				} else if (args[i].equalsIgnoreCase("-nolog")) {
					noLog = true;
				} else if (args[i].equalsIgnoreCase("-submit")) {
					tosubmit = true;
				} else if (args[i].equalsIgnoreCase("-direct")) {
					tosubmit = false;
				} else if (args[i].equalsIgnoreCase("-regex")) {
					i++;
					regex = args[i];
				} else if (args[i].equalsIgnoreCase("-elapse")) {
					i++;
					elapsed = Long.parseLong(args[i]);
				}
			}
		} catch (NumberFormatException e) {
			logger.error("Number Format exception at Rank "+i);
			return false;
		}
		if (fileInfo == null) {
			fileInfo = NOINFO;
		}
		if (rhosts != null && rule != null && localDirectory != null && statusfile != null && stopfile != null) {
			return true;
		}
		logger.error("All params are not set! Need at least (-to -rule -statusfile and -directory)");
		return false;
	}

	public static void main(String[] args) {
		InternalLoggerFactory.setDefaultFactory(new WaarpSlf4JLoggerFactory(null));
		if (logger == null) {
			logger = WaarpInternalLoggerFactory.getLogger(SpooledDirectoryTransfer.class);
		}
		if (!getParams(args)) {
			logger.error("Wrong initialization");
			if (DbConstant.admin != null && DbConstant.admin.isConnected) {
				DbConstant.admin.close();
			}
			ChannelUtils.stopLogger();
			System.exit(2);
		}

		Configuration.configuration.pipelineInit();
		NetworkTransaction networkTransaction = new NetworkTransaction();
		try {
			R66Future future = new R66Future(true);
			SpooledDirectoryTransfer spooled =
					new SpooledDirectoryTransfer(future, localDirectory, statusfile, stopfile,
							rule, fileInfo, ismd5, rhosts, block, regex, elapsed, tosubmit, noLog,
							networkTransaction);
			spooled.run();
			future.awaitUninterruptibly();
			logger.warn("End of SpooledDirectory with "+spooled.sent+" sent and "+spooled.error+" in error");
		} catch (Exception e) {
			logger.warn("exc", e);
		} finally {
			networkTransaction.closeAll();
			System.exit(0);
		}
	}

}