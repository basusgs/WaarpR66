/**
 *
 */
package openr66.protocol.localhandler;

import java.sql.Timestamp;
import java.util.List;

import goldengate.common.command.exception.CommandAbstractException;
import goldengate.common.command.exception.Reply421Exception;
import goldengate.common.command.exception.Reply530Exception;
import goldengate.common.exception.FileTransferException;
import goldengate.common.file.DataBlock;
import goldengate.common.logging.GgInternalLogger;
import goldengate.common.logging.GgInternalLoggerFactory;
import openr66.context.ErrorCode;
import openr66.context.R66Result;
import openr66.context.R66Session;
import openr66.context.filesystem.R66Dir;
import openr66.context.filesystem.R66File;
import openr66.context.task.exception.OpenR66RunnerErrorException;
import openr66.context.task.exception.OpenR66RunnerException;
import openr66.database.DbConstant;
import openr66.database.DbPreparedStatement;
import openr66.database.data.DbRule;
import openr66.database.data.DbTaskRunner;
import openr66.database.data.AbstractDbData.UpdatedInfo;
import openr66.database.exception.OpenR66DatabaseException;
import openr66.database.exception.OpenR66DatabaseNoConnectionError;
import openr66.database.exception.OpenR66DatabaseNoDataException;
import openr66.database.exception.OpenR66DatabaseSqlError;
import openr66.protocol.configuration.Configuration;
import openr66.protocol.exception.OpenR66Exception;
import openr66.protocol.exception.OpenR66ExceptionTrappedFactory;
import openr66.protocol.exception.OpenR66ProtocolBusinessCancelException;
import openr66.protocol.exception.OpenR66ProtocolBusinessException;
import openr66.protocol.exception.OpenR66ProtocolBusinessNoWriteBackException;
import openr66.protocol.exception.OpenR66ProtocolBusinessQueryAlreadyFinishedException;
import openr66.protocol.exception.OpenR66ProtocolBusinessStopException;
import openr66.protocol.exception.OpenR66ProtocolNetworkException;
import openr66.protocol.exception.OpenR66ProtocolNoConnectionException;
import openr66.protocol.exception.OpenR66ProtocolNoDataException;
import openr66.protocol.exception.OpenR66ProtocolNoSslException;
import openr66.protocol.exception.OpenR66ProtocolNotAuthenticatedException;
import openr66.protocol.exception.OpenR66ProtocolPacketException;
import openr66.protocol.exception.OpenR66ProtocolRemoteShutdownException;
import openr66.protocol.exception.OpenR66ProtocolShutdownException;
import openr66.protocol.exception.OpenR66ProtocolSystemException;
import openr66.protocol.localhandler.packet.AbstractLocalPacket;
import openr66.protocol.localhandler.packet.AuthentPacket;
import openr66.protocol.localhandler.packet.ConnectionErrorPacket;
import openr66.protocol.localhandler.packet.DataPacket;
import openr66.protocol.localhandler.packet.EndTransferPacket;
import openr66.protocol.localhandler.packet.ErrorPacket;
import openr66.protocol.localhandler.packet.InformationPacket;
import openr66.protocol.localhandler.packet.LocalPacketFactory;
import openr66.protocol.localhandler.packet.RequestPacket;
import openr66.protocol.localhandler.packet.ShutdownPacket;
import openr66.protocol.localhandler.packet.StartupPacket;
import openr66.protocol.localhandler.packet.TestPacket;
import openr66.protocol.localhandler.packet.ValidPacket;
import openr66.protocol.networkhandler.NetworkTransaction;
import openr66.protocol.utils.ChannelUtils;
import openr66.protocol.utils.FileUtils;

import org.dom4j.Document;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;

/**
 * The local server handler handles real end file operations.
 *
 * @author frederic bregier
 */
@ChannelPipelineCoverage("one")
public class LocalServerHandler extends SimpleChannelHandler {
    /**
     * Internal Logger
     */
    private static final GgInternalLogger logger = GgInternalLoggerFactory
            .getLogger(LocalServerHandler.class);

    /**
     * Session
     */
    private R66Session session;
    /**
     * Status of the current request
     */
    private boolean status = false;
    /**
     * Local Channel Reference
     */
    private LocalChannelReference localChannelReference;

    /*
     * (non-Javadoc)
     *
     * @see
     * org.jboss.netty.channel.SimpleChannelHandler#channelClosed(org.jboss.
     * netty.channel.ChannelHandlerContext,
     * org.jboss.netty.channel.ChannelStateEvent)
     */
    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
        logger.info("Local Server Channel Closed: " +
                status +
                " {} {}",
                (localChannelReference != null? localChannelReference
                        : "no LocalChannelReference"), (session.getRunner() != null ?
                            session.getRunner().toShortString() : "no runner"));
        // FIXME clean session objects like files
        if (localChannelReference != null &&
                !localChannelReference.getFutureRequest().isDone()) {
            if (!status) {
                logger.error("Finalize BUT SHOULD NOT");
            }
            R66Result finalValue = new R66Result(
                    new OpenR66ProtocolSystemException("Finalize at close time"),
                    session, true, ErrorCode.FinalOp); // True since closed
            try {
                session.setFinalizeTransfer(status, finalValue);
            } catch (OpenR66RunnerErrorException e1) {
                if (status) {
                    localChannelReference.validateRequest(finalValue);
                } else {
                    localChannelReference.invalidateRequest(finalValue);
                }
            } catch (OpenR66ProtocolSystemException e1) {
                if (status) {
                    localChannelReference.validateRequest(finalValue);
                } else {
                    localChannelReference.invalidateRequest(finalValue);
                }
            }
        }
        session.clear();
        if (localChannelReference != null) {
            if (localChannelReference.getDbSession() != null) {
                localChannelReference.getDbSession().endUseConnection();
                logger.info("End Use Connection");
            }
            NetworkTransaction.removeNetworkChannel(localChannelReference
                    .getNetworkChannel());
        } else {
            logger
                    .error("Local Server Channel Closed but no LocalChannelReference: " +
                            e.getChannel().getId());
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.jboss.netty.channel.SimpleChannelHandler#channelConnected(org.jboss
     * .netty.channel.ChannelHandlerContext,
     * org.jboss.netty.channel.ChannelStateEvent)
     */
    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        session = new R66Session();
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.jboss.netty.channel.SimpleChannelHandler#messageReceived(org.jboss
     * .netty.channel.ChannelHandlerContext,
     * org.jboss.netty.channel.MessageEvent)
     */
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
            throws OpenR66Exception {
        // FIXME action as requested and answer if necessary
        final AbstractLocalPacket packet = (AbstractLocalPacket) e.getMessage();
        if (packet.getType() == LocalPacketFactory.STARTUPPACKET) {
            startup(e.getChannel(), (StartupPacket) packet);
        } else {
            if (localChannelReference == null) {
                logger.error("No LocalChannelReference at " +
                        packet.getClass().getName());
                localChannelReference.invalidateRequest(new R66Result(
                        new OpenR66ProtocolSystemException(
                                "No LocalChannelReference"), session, true,
                        ErrorCode.ConnectionImpossible));
                final ErrorPacket errorPacket = new ErrorPacket(
                        "No LocalChannelReference at " +
                                packet.getClass().getName(),
                                ErrorCode.ConnectionImpossible.getCode(),
                        ErrorPacket.FORWARDCLOSECODE);
                Channels.write(e.getChannel(), errorPacket)
                        .awaitUninterruptibly();
                ChannelUtils.close(e.getChannel());
                return;
            }
            switch (packet.getType()) {
                case LocalPacketFactory.AUTHENTPACKET: {
                    authent(e.getChannel(), (AuthentPacket) packet);
                    break;
                }
                    // Already done case LocalPacketFactory.STARTUPPACKET:
                case LocalPacketFactory.DATAPACKET: {
                    data(e.getChannel(), (DataPacket) packet);
                    break;
                }
                case LocalPacketFactory.VALIDPACKET: {
                    valid(e.getChannel(), (ValidPacket) packet);
                    break;
                }
                case LocalPacketFactory.ERRORPACKET: {
                    error(e.getChannel(), (ErrorPacket) packet);
                    break;
                }
                case LocalPacketFactory.CONNECTERRORPACKET: {
                    connectionError(e.getChannel(),
                            (ConnectionErrorPacket) packet);
                    break;
                }
                case LocalPacketFactory.REQUESTPACKET: {
                    request(e.getChannel(), (RequestPacket) packet);
                    break;
                }
                case LocalPacketFactory.SHUTDOWNPACKET: {
                    shutdown(e.getChannel(), (ShutdownPacket) packet);
                    break;
                }
                case LocalPacketFactory.STOPPACKET:
                case LocalPacketFactory.CANCELPACKET:
                case LocalPacketFactory.CONFIGSENDPACKET:
                case LocalPacketFactory.CONFIGRECVPACKET:
                case LocalPacketFactory.BANDWIDTHPACKET: {
                    logger.error("Unimplemented Mesg: " +
                            packet.getClass().getName());
                    localChannelReference.invalidateRequest(new R66Result(
                            new OpenR66ProtocolSystemException(
                                    "Not implemented"), session, true,
                            ErrorCode.Unimplemented));
                    final ErrorPacket errorPacket = new ErrorPacket(
                            "Unimplemented Mesg: " +
                                    packet.getClass().getName(),
                                    ErrorCode.Unimplemented.getCode(),
                            ErrorPacket.FORWARDCLOSECODE);
                    ChannelUtils.writeAbstractLocalPacket(localChannelReference, errorPacket).awaitUninterruptibly();
                    ChannelUtils.close(e.getChannel());
                    break;
                }
                case LocalPacketFactory.TESTPACKET: {
                    test(e.getChannel(), (TestPacket) packet);
                    break;
                }
                case LocalPacketFactory.ENDTRANSFERPACKET: {
                    endTransfer(e.getChannel(), (EndTransferPacket) packet);
                    break;
                }
                case LocalPacketFactory.INFORMATIONPACKET: {
                    information(e.getChannel(), (InformationPacket) packet);
                    break;
                }
                default: {
                    logger
                            .error("Unknown Mesg: " +
                                    packet.getClass().getName());
                    localChannelReference.invalidateRequest(new R66Result(
                            new OpenR66ProtocolSystemException(
                                    "Unknown Message"), session, true,
                            ErrorCode.Unimplemented));
                    final ErrorPacket errorPacket = new ErrorPacket(
                            "Unkown Mesg: " + packet.getClass().getName(),
                            ErrorCode.Unimplemented.getCode(), ErrorPacket.FORWARDCLOSECODE);
                    ChannelUtils.writeAbstractLocalPacket(localChannelReference, errorPacket).awaitUninterruptibly();
                    ChannelUtils.close(e.getChannel());
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.jboss.netty.channel.SimpleChannelHandler#exceptionCaught(org.jboss
     * .netty.channel.ChannelHandlerContext,
     * org.jboss.netty.channel.ExceptionEvent)
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        // inform clients
        OpenR66Exception exception = OpenR66ExceptionTrappedFactory
                .getExceptionFromTrappedException(e.getChannel(), e);
        ErrorCode code = null;
        if (exception != null) {
            boolean isAnswered = false;
            if (exception instanceof OpenR66ProtocolShutdownException) {
                logger.warn("Shutdown order received and going from: " +
                        session.getAuth().getUser());
                R66Result finalValue = new R66Result(exception, session, true,
                        ErrorCode.Shutdown);
                status = true;
                localChannelReference.validateEndTransfer(finalValue);
                try {
                    session.setFinalizeTransfer(status, finalValue);
                } catch (OpenR66RunnerErrorException e1) {
                    localChannelReference.validateRequest(finalValue);
                } catch (OpenR66ProtocolSystemException e1) {
                    localChannelReference.validateRequest(finalValue);
                }
                // dont'close, thread will do
                new Thread(new ChannelUtils()).start();
                // set global shutdown info and before close, send a valid
                // shutdown to all
                return;
            } else {
                if (localChannelReference.getFutureRequest().isDone()) {
                    R66Result result = localChannelReference.getFutureRequest()
                        .getResult();
                    if (result != null) {
                        isAnswered = result.isAnswered;
                    }
                }
                if (exception instanceof OpenR66ProtocolNoConnectionException) {
                    code = ErrorCode.ConnectionImpossible;
                    DbTaskRunner runner = session.getRunner();
                    if (runner != null) {
                        runner.stopOrCancelRunner(code);
                    }
                } else if (exception instanceof OpenR66ProtocolBusinessCancelException) {
                    code = ErrorCode.CanceledTransfer;
                    DbTaskRunner runner = session.getRunner();
                    if (runner != null) {
                        runner.stopOrCancelRunner(code);
                    }
                } else if (exception instanceof OpenR66ProtocolBusinessStopException) {
                    code = ErrorCode.StoppedTransfer;
                    DbTaskRunner runner = session.getRunner();
                    if (runner != null) {
                        runner.stopOrCancelRunner(code);
                    }
                } else if (exception instanceof OpenR66ProtocolBusinessQueryAlreadyFinishedException) {
                    code = ErrorCode.QueryAlreadyFinished;
                } else if (exception instanceof OpenR66RunnerException) {
                    code = ErrorCode.ExternalOp;
                } else if (exception instanceof OpenR66ProtocolNotAuthenticatedException) {
                    code = ErrorCode.BadAuthent;
                } else if (exception instanceof OpenR66ProtocolNetworkException) {
                    code = ErrorCode.Disconnection;
                    DbTaskRunner runner = session.getRunner();
                    if (runner != null) {
                        runner.stopOrCancelRunner(code);
                    }
                } else if (exception instanceof OpenR66ProtocolRemoteShutdownException) {
                    code = ErrorCode.Disconnection;
                    DbTaskRunner runner = session.getRunner();
                    if (runner != null) {
                        runner.stopOrCancelRunner(code);
                    }
                } else {
                    code = ErrorCode.Internal;
                }
                localChannelReference.invalidateRequest(new R66Result(
                        exception, session, true, code));
            }
            if (exception instanceof OpenR66ProtocolBusinessNoWriteBackException) {
                logger.error("Will close channel", exception);
                Channels.close(e.getChannel());
                return;
            } else if (exception instanceof OpenR66ProtocolNoConnectionException) {
                logger.error("Will close channel", exception);
                Channels.close(e.getChannel());
                return;
            }
            if (!isAnswered) {
                if (code == null || code == ErrorCode.Internal) {
                    code = ErrorCode.RemoteError;
                }
                final ErrorPacket errorPacket = new ErrorPacket(exception
                        .getMessage(),
                        code.getCode(), ErrorPacket.FORWARDCLOSECODE);
                try {
                    ChannelUtils.writeAbstractLocalPacket(localChannelReference, errorPacket).awaitUninterruptibly();
                } catch (OpenR66ProtocolPacketException e1) {
                    // should not be
                }
            }
            ChannelUtils.close(e.getChannel());
        } else {
            // Nothing to do
            return;
        }
    }
    /**
     * Startup of the session and the local channel reference
     * @param channel
     * @param packet
     * @throws OpenR66ProtocolPacketException
     */
    private void startup(Channel channel, StartupPacket packet)
            throws OpenR66ProtocolPacketException {
        localChannelReference = Configuration.configuration
                .getLocalTransaction().getFromId(packet.getLocalId());
        if (localChannelReference == null) {
            logger.error("Cannot startup");
            localChannelReference.invalidateRequest(new R66Result(
                    new OpenR66ProtocolSystemException(
                            "Cannot startup connection"), session, true,
                    ErrorCode.ConnectionImpossible));
            ErrorPacket error = new ErrorPacket("Cannot startup connection",
                    ErrorCode.ConnectionImpossible.getCode(), ErrorPacket.FORWARDCLOSECODE);
            Channels.write(channel, error).awaitUninterruptibly();
            // Cannot do writeBack(error, true);
            ChannelUtils.close(channel);
            return;
        }
        session.setLocalChannelReference(localChannelReference);
        Channels.write(channel, packet);
    }
    /**
     * Authentication
     * @param channel
     * @param packet
     * @throws OpenR66ProtocolPacketException
     */
    private void authent(Channel channel, AuthentPacket packet)
            throws OpenR66ProtocolPacketException {
        localChannelReference.getDbSession().useConnection();
        try {
            session.getAuth().connection(localChannelReference.getDbSession(),
                    packet.getHostId(), packet.getKey());
        } catch (Reply530Exception e1) {
            logger.error("Cannot connect: " + packet.getHostId(), e1);
            R66Result result = new R66Result(
                    new OpenR66ProtocolSystemException(
                            "Connection not allowed", e1), session, true,
                    ErrorCode.BadAuthent);
            localChannelReference.invalidateRequest(result);
            ErrorPacket error = new ErrorPacket("Connection not allowed",
                    ErrorCode.BadAuthent.getCode(),
                    ErrorPacket.FORWARDCLOSECODE);
            ChannelUtils.writeAbstractLocalPacket(localChannelReference, error).awaitUninterruptibly();
            localChannelReference.validateConnection(false, result);
            ChannelUtils.close(channel);
            return;
        } catch (Reply421Exception e1) {
            logger.error("Service unavailable: " + packet.getHostId(), e1);
            R66Result result = new R66Result(
                    new OpenR66ProtocolSystemException("Service unavailable",
                            e1), session, true,
                    ErrorCode.ConnectionImpossible);
            localChannelReference.invalidateRequest(result);
            ErrorPacket error = new ErrorPacket("Service unavailable",
                    ErrorCode.ConnectionImpossible.getCode(),
                    ErrorPacket.FORWARDCLOSECODE);
            ChannelUtils.writeAbstractLocalPacket(localChannelReference, error).awaitUninterruptibly();
            localChannelReference.validateConnection(false, result);
            ChannelUtils.close(channel);
            return;
        }
        R66Result result = new R66Result(session, true, ErrorCode.InitOk);
        localChannelReference.validateConnection(true, result);
        logger.info("Local Server Channel Validated: " +
                status +
                " {} ",
                (localChannelReference != null? localChannelReference
                        : "no LocalChannelReference"));
        if (packet.isToValidate()) {
            packet.validate(session.getAuth().isSsl());
            ChannelUtils.writeAbstractLocalPacket(localChannelReference, packet);
        }
    }
    /**
     * Receive a connection error
     * @param channel
     * @param packet
     */
    private void connectionError(Channel channel, ConnectionErrorPacket packet) {
        // FIXME do something according to the error
        logger.error(channel.getId() + ": " + packet.toString());
        localChannelReference.invalidateRequest(new R66Result(
                new OpenR66ProtocolSystemException(packet.getSheader()),
                session, true, ErrorCode.ConnectionImpossible));
        // True since closing
        Channels.close(channel);
    }
    /**
     * Class to throw an exception when the future is over
     * @author Frederic Bregier
     *
     */
    private class ExceptionChannelFutureListener implements ChannelFutureListener {
        private Exception e;
        public ExceptionChannelFutureListener(Exception e) {
            this.e = e;
        }
        /* (non-Javadoc)
         * @see org.jboss.netty.channel.ChannelFutureListener#operationComplete(org.jboss.netty.channel.ChannelFuture)
         */
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            throw e;
        }

    }
    /**
     * Receive a remote error
     * @param channel
     * @param packet
     * @throws OpenR66RunnerErrorException
     * @throws OpenR66ProtocolSystemException
     * @throws OpenR66ProtocolBusinessException
     */
    private void error(Channel channel, ErrorPacket packet)
            throws OpenR66RunnerErrorException, OpenR66ProtocolSystemException, OpenR66ProtocolBusinessException {
        // do something according to the error
        logger.error(channel.getId() + ": " + packet.toString());
        ErrorCode code = ErrorCode.getFromCode(packet.getSmiddle());
        OpenR66ProtocolBusinessException exception;
        if (code.code == ErrorCode.CanceledTransfer.code) {
            exception =
                new OpenR66ProtocolBusinessCancelException(packet.getSheader());
            int rank = 0;
            DbTaskRunner runner = this.session.getRunner();
            if (runner != null) {
                runner.setRankAtStartup(rank);
                runner.stopOrCancelRunner(code);
            }
            session.setFinalizeTransfer(false, new R66Result(exception, session,
                    true, code));
            // now try to inform other
            try {
                ChannelUtils.writeAbstractLocalPacket(localChannelReference, packet).
                    addListener(new ExceptionChannelFutureListener(exception));
            } catch (OpenR66ProtocolPacketException e) {
            }
            return;
        } else if (code.code == ErrorCode.StoppedTransfer.code) {
            exception =
                new OpenR66ProtocolBusinessStopException(packet.getSheader());
            String []vars = packet.getSheader().split(" ");
            String var = vars[vars.length-1];
            int rank = Integer.parseInt(var);
            DbTaskRunner runner = this.session.getRunner();
            if (runner != null) {
                if (rank < runner.getRank()) {
                    runner.setRankAtStartup(rank);
                }
                runner.stopOrCancelRunner(code);
            }
            session.setFinalizeTransfer(false, new R66Result(exception, session,
                    true, code));
            // now try to inform other
            try {
                ChannelUtils.writeAbstractLocalPacket(localChannelReference, packet).
                    addListener(new ExceptionChannelFutureListener(exception));
            } catch (OpenR66ProtocolPacketException e) {
            }
            return;
        } else if (code.code == ErrorCode.QueryAlreadyFinished.code) {
            exception =
                new OpenR66ProtocolBusinessQueryAlreadyFinishedException(packet.getSheader());
            DbTaskRunner runner = this.session.getRunner();
            if (runner != null) {
                // FIXME do the real end
                R66Result finalValue = new R66Result(session, true, ErrorCode.CompleteOk);
                try {
                    runner.finalizeRunner(localChannelReference, session.getFile(),
                            finalValue, true);
                    session.setFinalizeTransfer(true, new R66Result(exception, session,
                            true, code));
                } catch (OpenR66ProtocolSystemException e) {
                    logger.warn("Cannot validate runner: {}",runner.toShortString());
                    runner.changeUpdatedInfo(UpdatedInfo.INERROR);
                    runner.setErrorExecutionStatus(code);
                    try {
                        runner.update();
                    } catch (OpenR66DatabaseException e1) {
                    }
                    session.setFinalizeTransfer(false, new R66Result(exception, session,
                            true, code));
                }
                throw exception;
            }
        } else if (code.code == ErrorCode.BadAuthent.code) {
            exception =
                new OpenR66ProtocolNotAuthenticatedException(packet.toString());
        } else {
            exception =
                new OpenR66ProtocolBusinessNoWriteBackException(packet.toString());
        }
        session.setFinalizeTransfer(false, new R66Result(exception, session,
                true, code));
        throw exception;
    }

    private void endInitRequestInError(Channel channel, ErrorCode code,
            OpenR66Exception e1) throws OpenR66ProtocolPacketException {
        logger.error("TaskRunner initialisation in error");
        localChannelReference.invalidateRequest(new R66Result(
                new OpenR66ProtocolSystemException(
                        "TaskRunner initialisation in error", e1),
                session, true, code));
        ErrorPacket error = new ErrorPacket(
                "TaskRunner initialisation in error: "+e1
                        .getMessage(),
                        code.getCode(), ErrorPacket.FORWARDCLOSECODE);
        ChannelUtils.writeAbstractLocalPacket(localChannelReference, error).awaitUninterruptibly();
        ChannelUtils.close(channel);
    }

    private void endInitRequestInError(Channel channel, OpenR66Exception e1) throws OpenR66ProtocolPacketException {
        endInitRequestInError(channel, ErrorCode.RemoteError, e1);
    }
    /**
     * Receive a request
     * @param channel
     * @param packet
     * @throws OpenR66ProtocolNotAuthenticatedException
     * @throws OpenR66ProtocolNoDataException
     * @throws OpenR66ProtocolPacketException
     */
    private void request(Channel channel, RequestPacket packet)
            throws OpenR66ProtocolNotAuthenticatedException,
            OpenR66ProtocolNoDataException, OpenR66ProtocolPacketException {
        if (!session.isAuthenticated()) {
            throw new OpenR66ProtocolNotAuthenticatedException(
                    "Not authenticated");
        }
        DbRule rule;
        try {
            rule = new DbRule(localChannelReference.getDbSession(), packet.getRulename());
        } catch (OpenR66DatabaseException e) {
            logger.error("Rule is unknown: " + packet.getRulename(), e);
            throw new OpenR66ProtocolNoDataException(e);
        }
        if (packet.isToValidate()) {
            if (!rule.checkHostAllow(session.getAuth().getUser())) {
                throw new OpenR66ProtocolNotAuthenticatedException(
                        "Rule is not allowed for the remote host");
            }
        }
        session.setBlockSize(packet.getBlocksize());
        DbTaskRunner runner;
        if (packet.getSpecialId() != DbConstant.ILLEGALVALUE) {
            // Reload or create
            String requested = DbTaskRunner.getRequested(session, packet);
            String requester = DbTaskRunner.getRequester(session, packet);
            if (packet.isToValidate()) {
                // Id could be a creation or a reload
                // Try reload
                try {
                    runner = new DbTaskRunner(localChannelReference.getDbSession(),
                            session, rule, packet.getSpecialId(),
                            requester, requested);
                    if (runner.isAllDone()) {
                        // truly an error since done
                        endInitRequestInError(channel,
                                ErrorCode.QueryAlreadyFinished,
                            new OpenR66RunnerErrorException(
                               "The TransferId is associated with a Transfer already finished: "+
                               packet.getSpecialId()));
                        return;
                    }
                    LocalChannelReference lcr =
                        Configuration.configuration.getLocalTransaction().
                        getFromRequest(requested+" "+requester+" "+packet.getSpecialId());
                    if (lcr != null) {
                        // truly an error since still running
                        endInitRequestInError(channel,
                                ErrorCode.QueryAlreadyFinished,
                            new OpenR66RunnerErrorException(
                               "The TransferId is associated with a Transfer still running: "+
                               packet.getSpecialId()));
                        return;
                    }
                    // ok to restart
                } catch (OpenR66DatabaseNoDataException e) {
                    // Reception of request from requester host
                    boolean isRetrieve = RequestPacket.isRecvMode(packet.getMode());
                    try {
                        runner = new DbTaskRunner(localChannelReference.getDbSession(),
                                session, rule, isRetrieve, packet);
                    } catch (OpenR66DatabaseException e1) {
                        endInitRequestInError(channel, e);
                        return;
                    }
                } catch (OpenR66DatabaseException e) {
                    endInitRequestInError(channel, e);
                    return;
                }
                // Change the SpecialID! => could generate an error ? FIXME
                packet.setSpecialId(runner.getSpecialId());
            } else {
                // Id should be a reload
                try {
                    runner = new DbTaskRunner(localChannelReference.getDbSession(),
                            session, rule, packet.getSpecialId(),
                            requester, requested);
                    if (runner.isInError() && runner.restart(false)) {
                        // ok
                    }
                } catch (OpenR66DatabaseException e) {
                    if (localChannelReference.getDbSession() == null) {
                        //Special case of no database client
                        boolean isRetrieve = (!RequestPacket.isRecvMode(packet.getMode()));
                        try {
                            runner = new DbTaskRunner(localChannelReference.getDbSession(),
                                    session, rule, isRetrieve, packet);
                        } catch (OpenR66DatabaseException e1) {
                            endInitRequestInError(channel, e1);
                            return;
                        }
                    } else {
                        endInitRequestInError(channel, e);
                        return;
                    }
                }
            }
        } else {
            // Very new request
            // FIXME should not be the case (the requester should always set the id)
            logger.error("SHOULD NOT BE THE CASE");
            boolean isRetrieve = packet.isRetrieve();
            if (!packet.isToValidate()) {
                isRetrieve = !isRetrieve;
            }
            try {
                runner = new DbTaskRunner(localChannelReference.getDbSession(),
                        session, rule, isRetrieve, packet);
            } catch (OpenR66DatabaseException e) {
                endInitRequestInError(channel, e);
                return;
            }
            packet.setSpecialId(runner.getSpecialId());
        }
        // Receiver can specify a rank different from database
        if (runner.isSender()) {
            runner.setRankAtStartup(packet.getRank());
        } else if (runner.getRank() > packet.getRank()) {
            // if receiver, change only if current rank is upper proposed rank
            runner.setRankAtStartup(packet.getRank());
        }
        try {
            session.setRunner(runner);
        } catch (OpenR66RunnerErrorException e) {
            try {
                runner.saveStatus();
            } catch (OpenR66RunnerErrorException e1) {
                logger.error("Cannot save Status: " + runner, e1);
            }
            logger.error("PreTask in error", e);
            localChannelReference.invalidateRequest(new R66Result(e, session,
                    true, ErrorCode.ExternalOp));
            ErrorPacket error = new ErrorPacket("PreTask in error: "+e
                    .getMessage(), ErrorCode.ExternalOp.getCode(), ErrorPacket.FORWARDCLOSECODE);
            ChannelUtils.writeAbstractLocalPacket(localChannelReference, error).awaitUninterruptibly();
            ChannelUtils.close(channel);
            return;
        }
        session.setReady(true);
        Configuration.configuration.getLocalTransaction().setFromId(runner, localChannelReference);
        // inform back
        if (packet.isToValidate()) {
            if (runner.isSender()) {
                // In case Wildcard was used
                logger.info("New FILENAME: {}", runner.getOriginalFilename());
                packet.setFilename(runner.getOriginalFilename());
            } else {
                packet.setRank(runner.getRank());
            }
            packet.validate();
            ChannelUtils.writeAbstractLocalPacket(localChannelReference, packet).awaitUninterruptibly();
        }
        // if retrieve => START the retrieve operation except if in Send Through mode
        if (runner.isSender()) {
            if (RequestPacket.isSendThroughMode(packet.getMode())) {
                // it is legal to send data from now
                logger.info("Now ready to continue with send through");
                localChannelReference.validateEndTransfer(
                        new R66Result(session, false, ErrorCode.PreProcessingOk));
            } else {
                // Automatically send data now
                NetworkTransaction.runRetrieve(session, channel);
            }
        }
    }
    /**
     * Receive a data
     * @param channel
     * @param packet
     * @throws OpenR66ProtocolNotAuthenticatedException
     * @throws OpenR66ProtocolBusinessException
     * @throws OpenR66ProtocolPacketException
     */
    private void data(Channel channel, DataPacket packet)
            throws OpenR66ProtocolNotAuthenticatedException,
            OpenR66ProtocolBusinessException, OpenR66ProtocolPacketException {
        if (!session.isAuthenticated()) {
            throw new OpenR66ProtocolNotAuthenticatedException(
                    "Not authenticated");
        }
        if (!session.isReady()) {
            throw new OpenR66ProtocolBusinessException("No request prepared");
        }
        if (session.getRunner().isSender()) {
            throw new OpenR66ProtocolBusinessException(
                    "Not in receive MODE but receive a packet");
        }
        if (packet.getPacketRank() != session.getRunner().getRank()) {
            logger.warn("Bad RANK: " + packet.getPacketRank() + " : " +
                    session.getRunner().getRank());
        }
        DataBlock dataBlock = new DataBlock();
        // if MD5 check MD5
        if (RequestPacket.isMD5Mode(session.getRunner().getMode())) {
            if (!packet.isKeyValid()) {
                // Wrong packet
                try {
                    session.setFinalizeTransfer(false, new R66Result(
                            new OpenR66ProtocolPacketException(
                                    "Wrong Packet MD5"), session, true,
                            ErrorCode.MD5Error));
                } catch (OpenR66RunnerErrorException e1) {
                } catch (OpenR66ProtocolSystemException e1) {
                }
                ErrorPacket error = new ErrorPacket(
                        "Transfer in error due to bad MD5",
                        ErrorCode.MD5Error.getCode(), ErrorPacket.FORWARDCLOSECODE);
                ChannelUtils.writeAbstractLocalPacket(localChannelReference, error).awaitUninterruptibly();
                ChannelUtils.close(channel);
                return;
            }
        }
        if (RequestPacket.isRecvThroughMode(session.getRunner().getMode())) {
            localChannelReference.getRecvThroughHandler().writeChannelBuffer(packet.getData());
            session.getRunner().incrementRank();
        } else {
            dataBlock.setBlock(packet.getData());
            try {
                session.getFile().writeDataBlock(dataBlock);
                session.getRunner().incrementRank();
            } catch (FileTransferException e) {
                try {
                    session.setFinalizeTransfer(false, new R66Result(
                            new OpenR66ProtocolSystemException(e), session, true,
                            ErrorCode.TransferError));
                } catch (OpenR66RunnerErrorException e1) {
                } catch (OpenR66ProtocolSystemException e1) {
                }
                ErrorPacket error = new ErrorPacket("Transfer in error",
                        ErrorCode.TransferError.getCode(), ErrorPacket.FORWARDCLOSECODE);
                ChannelUtils.writeAbstractLocalPacket(localChannelReference, error).awaitUninterruptibly();
                ChannelUtils.close(channel);
                return;
            }
        }
    }
    /**
     * Test reception
     * @param channel
     * @param packet
     * @throws OpenR66ProtocolNotAuthenticatedException
     * @throws OpenR66ProtocolPacketException
     */
    private void test(Channel channel, TestPacket packet)
            throws OpenR66ProtocolNotAuthenticatedException,
            OpenR66ProtocolPacketException {
        if (!session.isAuthenticated()) {
            throw new OpenR66ProtocolNotAuthenticatedException(
                    "Not authenticated");
        }
        // simply write back after+1
        packet.update();
        if (packet.getType() == LocalPacketFactory.VALIDPACKET) {
            ValidPacket validPacket = new ValidPacket(packet.toString(), null,
                    LocalPacketFactory.TESTPACKET);
            R66Result result = new R66Result(session, true,
                    ErrorCode.CompleteOk);
            result.other = validPacket;
            localChannelReference.validateRequest(result);
            ChannelUtils.writeAbstractLocalPacket(localChannelReference, validPacket).awaitUninterruptibly();
            logger.warn("Valid TEST MESSAGE: " +packet.toString()+" "+
                        localChannelReference.toString());
            Channels.close(channel);
        } else {
            ChannelUtils.writeAbstractLocalPacket(localChannelReference, packet);
        }
    }
    /**
     * Receive an End of Transfer
     * @param channel
     * @param packet
     * @throws OpenR66RunnerErrorException
     * @throws OpenR66ProtocolSystemException
     * @throws OpenR66ProtocolNotAuthenticatedException
     */
    private void endTransfer(Channel channel, EndTransferPacket packet)
            throws OpenR66RunnerErrorException, OpenR66ProtocolSystemException,
            OpenR66ProtocolNotAuthenticatedException {
        if (!session.isAuthenticated()) {
            throw new OpenR66ProtocolNotAuthenticatedException(
                    "Not authenticated");
        }
        // Check end of transfer
        if (packet.isToValidate()) {
            if (!localChannelReference.getFutureRequest().isDone()) {
                // Now can send validation
                packet.validate();
                try {
                    ChannelUtils.writeAbstractLocalPacket(localChannelReference,
                            packet).awaitUninterruptibly();
                } catch (OpenR66ProtocolPacketException e) {
                    // ignore
                }
                status = true;
                // Finish with post Operation
                R66Result result = new R66Result(session, false,
                        ErrorCode.TransferOk);
                session.setFinalizeTransfer(true, result);
            } else {
                // in error due to a previous status (like bad MD5)
                logger
                        .error("Error since end of transfer signaled but already done");
                Channels.close(channel);
            }
        } else {
            // Validation of end of transfer
            status = true;
            R66Result result = new R66Result(session, false,
                    ErrorCode.TransferOk);
            session.setFinalizeTransfer(true, result);
        }
    }
    /**
     * Receive a request of information
     * @param channel
     * @param packet
     * @throws CommandAbstractException
     * @throws OpenR66ProtocolNotAuthenticatedException
     * @throws OpenR66ProtocolNoDataException
     * @throws OpenR66ProtocolPacketException
     */
    private void information(Channel channel, InformationPacket packet)
            throws OpenR66ProtocolNotAuthenticatedException,
            OpenR66ProtocolNoDataException, OpenR66ProtocolPacketException {
        if (!session.isAuthenticated()) {
            throw new OpenR66ProtocolNotAuthenticatedException(
                    "Not authenticated");
        }
        byte request = packet.getRequest();
        DbRule rule;
        try {
            rule = new DbRule(localChannelReference.getDbSession(), packet.getRulename());
        } catch (OpenR66DatabaseException e) {
            logger.error("Rule is unknown: " + packet.getRulename(), e);
            throw new OpenR66ProtocolNoDataException(e);
        }
        try {
            if (RequestPacket.isRecvMode(rule.mode)) {
                session.getDir().changeDirectory(rule.workPath);
            } else {
                session.getDir().changeDirectory(rule.sendPath);
            }

            if (request == InformationPacket.ASKENUM.ASKLIST.ordinal() ||
                    request == InformationPacket.ASKENUM.ASKMLSLIST.ordinal()) {
                // ls or mls from current directory
                List<String> list;
                if (request == InformationPacket.ASKENUM.ASKLIST.ordinal()) {
                    list = session.getDir().list(packet.getFilename());
                } else{
                    list = session.getDir().listFull(packet.getFilename(), false);
                }

                StringBuilder builder = new StringBuilder();
                for (String elt: list) {
                    builder.append(elt);
                    builder.append("\n");
                }
                ValidPacket validPacket = new ValidPacket(builder.toString(), ""+list.size(),
                        LocalPacketFactory.INFORMATIONPACKET);
                R66Result result = new R66Result(session, true,
                        ErrorCode.CompleteOk);
                result.other = validPacket;
                localChannelReference.validateEndTransfer(result);
                localChannelReference.validateRequest(result);
                ChannelUtils.writeAbstractLocalPacket(localChannelReference,
                        validPacket).awaitUninterruptibly();
                Channels.close(channel);
            } else {
                // ls pr mls from current directory and filename
                R66File file = (R66File) session.getDir().setFile(packet.getFilename(), false);
                String sresult = null;
                if (request == InformationPacket.ASKENUM.ASKEXIST.ordinal()) {
                    sresult = ""+file.exists();
                } else if (request == InformationPacket.ASKENUM.ASKMLSDETAIL.ordinal()) {
                    sresult = session.getDir().fileFull(packet.getFilename(), false);
                    String [] list = sresult.split("\n");
                    sresult = list[1];
                } else {
                    ErrorPacket error = new ErrorPacket("Unknown Request "+request,
                            ErrorCode.Warning.getCode(), ErrorPacket.FORWARDCLOSECODE);
                    ChannelUtils.writeAbstractLocalPacket(localChannelReference, error).
                        awaitUninterruptibly();
                    ChannelUtils.close(channel);
                    return;
                }
                ValidPacket validPacket = new ValidPacket(sresult, "1",
                        LocalPacketFactory.INFORMATIONPACKET);
                R66Result result = new R66Result(session, true,
                        ErrorCode.CompleteOk);
                result.other = validPacket;
                localChannelReference.validateEndTransfer(result);
                localChannelReference.validateRequest(result);
                ChannelUtils.writeAbstractLocalPacket(localChannelReference,
                        validPacket).awaitUninterruptibly();
                Channels.close(channel);
            }
        } catch (CommandAbstractException e) {
            ErrorPacket error = new ErrorPacket("Error while Request "+request+" "+e.getMessage(),
                    ErrorCode.Internal.getCode(), ErrorPacket.FORWARDCLOSECODE);
            ChannelUtils.writeAbstractLocalPacket(localChannelReference, error).
                awaitUninterruptibly();
            ChannelUtils.close(channel);
        }
    }
    /**
     * Stop or Cancel a Runner
     * @param id
     * @param reqd
     * @param reqr
     * @param code
     * @return True if correctly stopped or canceled
     */
    private boolean stopOrCancelRunner(long id, String reqd, String reqr, ErrorCode code) {
        try {
            DbTaskRunner taskRunner =
                new DbTaskRunner(localChannelReference.getDbSession(), session,
                        null, id, reqr, reqd);
            return taskRunner.stopOrCancelRunner(code);
        } catch (OpenR66DatabaseException e) {
        }
        return false;
    }
    /**
     * Receive a validation
     * @param channel
     * @param packet
     * @throws OpenR66ProtocolNotAuthenticatedException
     * @throws OpenR66RunnerErrorException
     * @throws OpenR66ProtocolSystemException
     * @throws OpenR66ProtocolBusinessException
     */
    private void valid(Channel channel, ValidPacket packet)
            throws OpenR66ProtocolNotAuthenticatedException,
            OpenR66RunnerErrorException, OpenR66ProtocolSystemException, OpenR66ProtocolBusinessException {
        if (packet.getTypeValid() != LocalPacketFactory.SHUTDOWNPACKET &&
                !session.isAuthenticated()) {
            throw new OpenR66ProtocolNotAuthenticatedException(
                    "Not authenticated");
        }
        switch (packet.getTypeValid()) {
            case LocalPacketFactory.REQUESTPACKET: {
                // Validate the last post action on a transfer from receiver remote host
                logger.info("Valid Request {} {}",
                        localChannelReference,
                        packet);
                // end of request
                localChannelReference.validateRequest(localChannelReference
                        .getFutureEndTransfer().getResult());
                Channels.close(channel);
                break;
            }
            case LocalPacketFactory.SHUTDOWNPACKET: {
                logger.warn("Shutdown received so Will close channel" +
                        localChannelReference.toString());
                NetworkTransaction
                        .shuttingdownNetworkChannel(localChannelReference
                                .getNetworkChannel());
                R66Result result = new R66Result(
                        new OpenR66ProtocolShutdownException(), session, true,
                        ErrorCode.Shutdown);
                result.other = packet;
                if (session.getRunner() != null &&
                        session.getRunner().isInTransfer()) {
                    String srank = packet.getSmiddle();
                    DbTaskRunner runner = session.getRunner();
                    if (srank != null && srank.length() > 0) {
                        // Save last rank from remote point of view
                        try {
                            int rank = Integer.parseInt(srank);
                            runner.setRankAtStartup(rank);
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                        session.setFinalizeTransfer(false, result);
                    } else if (! runner.isSender()) {
                        // is receiver so informs back for the rank to use next time
                        int rank = runner.getRank();
                        packet.setSmiddle(Integer.toString(rank));
                        try {
                            runner.saveStatus();
                        } catch (OpenR66RunnerErrorException e) {
                        }
                        session.setFinalizeTransfer(false, result);
                        try {
                            ChannelUtils.writeAbstractLocalPacket(localChannelReference, packet);
                        } catch (OpenR66ProtocolPacketException e) {
                        }
                    }
                } else {
                    session.setFinalizeTransfer(false, result);
                }
                Channels.close(channel);
                break;
            }
            case LocalPacketFactory.STOPPACKET:
            case LocalPacketFactory.CANCELPACKET: {
                // Authentication must be the local server
                try {
                    if (!session.getAuth().getUser().equals(
                            Configuration.configuration.getHostId(session.getAuth().isSsl()))) {
                        throw new OpenR66ProtocolNotAuthenticatedException(
                                "Not correctly authenticated");
                    }
                } catch (OpenR66ProtocolNoSslException e1) {
                    throw new OpenR66ProtocolNotAuthenticatedException(
                            "Not correctly authenticated since SSL is not supported", e1);
                }
                // header = ?; middle = requested+blank+requester+blank+specialId
                LocalChannelReference lcr =
                    Configuration.configuration.getLocalTransaction().
                    getFromRequest(packet.getSmiddle());
                // stop the current transfer
                R66Result resulttest;
                ErrorCode code = (packet.getTypeValid() == LocalPacketFactory.STOPPACKET) ?
                        ErrorCode.StoppedTransfer : ErrorCode.CanceledTransfer;
                if (lcr != null) {
                    int rank = 0;
                    if (code == ErrorCode.StoppedTransfer && lcr.getSession() != null) {
                        DbTaskRunner taskRunner = lcr.getSession().getRunner();
                        if (taskRunner != null) {
                            if (taskRunner.isSender()) {
                                // FIXME should we decrease ?
                                rank = taskRunner.getRank();
                            } else {
                                // FIXME should we decrease ?
                                rank = taskRunner.getRank();
                            }
                            if (rank < 0) {
                                rank = 0;
                            }
                        }
                    }
                    ErrorPacket error = new ErrorPacket(code.name()+" "+rank,
                            code.getCode(), ErrorPacket.FORWARDCLOSECODE);
                    try {
                        //XXX ChannelUtils.writeAbstractLocalPacket(lcr, error);
                        // inform local instead of remote
                        ChannelUtils.writeAbstractLocalPacketToLocal(lcr, error);
                    } catch (Exception e) {
                    }
                    resulttest = new R66Result(session, true,
                            ErrorCode.CompleteOk);
                } else {
                    // Transfer is not running
                    // but maybe need action on database
                    String [] keys = packet.getSmiddle().split(" ");
                    long id = Long.parseLong(keys[2]);
                    if (stopOrCancelRunner(id, keys[0], keys[1], code)) {
                        resulttest = new R66Result(session, true,
                                ErrorCode.CompleteOk);
                    } else {
                        resulttest = new R66Result(session, true,
                            ErrorCode.TransferOk);
                    }
                }
                // inform back the requester
                ValidPacket valid = new ValidPacket(packet.getSmiddle(), resulttest.code.getCode(),
                        LocalPacketFactory.REQUESTUSERPACKET);
                resulttest.other = packet;
                localChannelReference.validateRequest(resulttest);
                try {
                    ChannelUtils.writeAbstractLocalPacket(localChannelReference,
                            valid).awaitUninterruptibly();
                } catch (OpenR66ProtocolPacketException e) {
                }
                Channels.close(channel);
                break;
            }
            case LocalPacketFactory.REQUESTUSERPACKET: {
                // Validate user request
                R66Result resulttest = new R66Result(session, true,
                        ErrorCode.getFromCode(packet.getSmiddle()));
                resulttest.other = packet;
                localChannelReference.validateRequest(resulttest);
                Channels.close(channel);
                break;
            }
            case LocalPacketFactory.LOGPACKET:
            case LocalPacketFactory.LOGPURGEPACKET: {
                // should be from the local server or from an authorized hosts: isAdmin
                if (!session.getAuth().isAdmin()) {
                    throw new OpenR66ProtocolNotAuthenticatedException(
                            "Not correctly authenticated");
                }
                String sstart = packet.getSheader();
                String sstop = packet.getSmiddle();
                boolean isPurge = (packet.getTypeValid() == LocalPacketFactory.LOGPURGEPACKET) ?
                        true : false;
                Timestamp start = (sstart == null || sstart.length() == 0) ? null :
                    Timestamp.valueOf(sstart);
                Timestamp stop = (sstop == null || sstop.length() == 0) ? null :
                    Timestamp.valueOf(sstop);
                // create export of log and optionally purge them from database
                DbPreparedStatement getValid = null;
                Document document = null;
                try {
                    getValid =
                        DbTaskRunner.getLogPrepareStament(localChannelReference.getDbSession(),
                                start, stop);
                    document = DbTaskRunner.writeXML(getValid);
                } catch (OpenR66DatabaseNoConnectionError e1) {
                    throw new OpenR66ProtocolBusinessException(e1);
                } catch (OpenR66DatabaseSqlError e1) {
                    throw new OpenR66ProtocolBusinessException(e1);
                } finally {
                    if (getValid != null) {
                        getValid.realClose();
                    }
                }
                String filename = Configuration.configuration.baseDirectory+
                    Configuration.configuration.archivePath+R66Dir.SEPARATOR+
                    Configuration.configuration.HOST_ID+"_"+System.currentTimeMillis()+
                    "_runners.xml";
                FileUtils.writeXML(filename, null, document);
                // in case of purge
                int nb = 0;
                if (isPurge) {
                    // purge in same interval all runners with globallaststep
                    // as ALLDONETASK or ERRORTASK
                    try {
                        nb = DbTaskRunner.purgeLogPrepareStament(
                                localChannelReference.getDbSession(),
                                start, stop);
                    } catch (OpenR66DatabaseNoConnectionError e) {
                        throw new OpenR66ProtocolBusinessException(e);
                    } catch (OpenR66DatabaseSqlError e) {
                        throw new OpenR66ProtocolBusinessException(e);
                    }
                }
                R66Result result = new R66Result(session, true, ErrorCode.CompleteOk);
                // Now answer
                ValidPacket valid = new ValidPacket(filename+" "+nb, result.code.getCode(),
                        LocalPacketFactory.REQUESTUSERPACKET);
                localChannelReference.validateRequest(result);
                try {
                    ChannelUtils.writeAbstractLocalPacket(localChannelReference,
                            valid).awaitUninterruptibly();
                } catch (OpenR66ProtocolPacketException e) {
                }
                Channels.close(channel);
                break;
            }
            case LocalPacketFactory.INFORMATIONPACKET: {
                // Validate user request
                R66Result resulttest = new R66Result(session, true,
                        ErrorCode.CompleteOk);
                resulttest.other = packet;
                localChannelReference.validateRequest(resulttest);
                Channels.close(channel);
                break;
            }
            case LocalPacketFactory.VALIDPACKET: {
                // test for restarting transfer
                // header = ?; middle = requested+blank+requester+blank+specialId
                LocalChannelReference lcr =
                    Configuration.configuration.getLocalTransaction().
                    getFromRequest(packet.getSmiddle());
                R66Result resulttest;
                ErrorCode code;
                if (lcr != null) {
                    code = ErrorCode.Running;
                    resulttest = new R66Result(session, true,
                            code);
                } else {
                    // Transfer is not running
                    // but maybe need action on database
                    String [] keys = packet.getSmiddle().split(" ");
                    long id = Long.parseLong(keys[2]);
                    try {
                        DbTaskRunner taskRunner =
                            new DbTaskRunner(localChannelReference.getDbSession(), session,
                                    null, id, keys[1], keys[0]);
                        if (taskRunner.restart(true)) {
                            code = ErrorCode.PreProcessingOk;
                        } else {
                            code = ErrorCode.CompleteOk;
                        }
                    } catch (OpenR66DatabaseException e) {
                        code = ErrorCode.RemoteError;
                    }
                    resulttest = new R66Result(session, true,
                            code);
                }
                // inform back the requester
                ValidPacket valid = new ValidPacket(packet.getSmiddle(), resulttest.code.getCode(),
                        LocalPacketFactory.REQUESTUSERPACKET);
                resulttest.other = packet;
                localChannelReference.validateRequest(resulttest);
                try {
                    ChannelUtils.writeAbstractLocalPacket(localChannelReference,
                            valid).awaitUninterruptibly();
                } catch (OpenR66ProtocolPacketException e) {
                }
                Channels.close(channel);
                break;
            }
            case LocalPacketFactory.BANDWIDTHPACKET: {
                // should be from the local server or from an authorized hosts: isAdmin
                if (!session.getAuth().isAdmin()) {
                    throw new OpenR66ProtocolNotAuthenticatedException(
                            "Not correctly authenticated");
                }
                String []splitglobal  = packet.getSheader().split(" ");
                String []splitsession = packet.getSmiddle().split(" ");
                long wgl  = Long.parseLong(splitglobal[0]);
                long rgl  = Long.parseLong(splitglobal[1]);
                long wsl  = Long.parseLong(splitsession[0]);
                long rsl  = Long.parseLong(splitsession[1]);
                if (wgl < 0) {
                    wgl = Configuration.configuration.serverGlobalWriteLimit;
                }
                if (rgl < 0) {
                    rgl = Configuration.configuration.serverGlobalReadLimit;
                }
                if (wsl < 0) {
                    wsl = Configuration.configuration.serverChannelWriteLimit;
                }
                if (rsl < 0) {
                    rsl = Configuration.configuration.serverChannelReadLimit;
                }
                Configuration.configuration.changeNetworkLimit(wgl, rgl, wsl, rsl,
                        Configuration.configuration.delayLimit);
                R66Result result = new R66Result(session, true, ErrorCode.CompleteOk);
                // Now answer
                ValidPacket valid = new ValidPacket("Bandwidth changed", result.code.getCode(),
                        LocalPacketFactory.REQUESTUSERPACKET);
                localChannelReference.validateRequest(result);
                try {
                    ChannelUtils.writeAbstractLocalPacket(localChannelReference,
                            valid).awaitUninterruptibly();
                } catch (OpenR66ProtocolPacketException e) {
                }
                Channels.close(channel);
                break;
            }
            case LocalPacketFactory.TESTPACKET: {
                logger.warn("Valid TEST MESSAGE: " +packet.toString()+" "+
                        localChannelReference.toString());
                R66Result resulttest = new R66Result(session, true,
                        ErrorCode.CompleteOk);
                resulttest.other = packet;
                localChannelReference.validateRequest(resulttest);
                Channels.close(channel);
                break;
            }
            default:
                logger.warn("Validation is ignored: " + packet.getTypeValid());
        }
    }
    /**
     * Receive a Shutdown request
     * @param channel
     * @param packet
     * @throws OpenR66ProtocolShutdownException
     * @throws OpenR66ProtocolNotAuthenticatedException
     * @throws OpenR66ProtocolBusinessException
     */
    private void shutdown(Channel channel, ShutdownPacket packet)
            throws OpenR66ProtocolShutdownException,
            OpenR66ProtocolNotAuthenticatedException,
            OpenR66ProtocolBusinessException {
        if (!session.isAuthenticated()) {
            throw new OpenR66ProtocolNotAuthenticatedException(
                    "Not authenticated");
        }
        if (session.getAuth().isAdmin() &&
                Configuration.configuration.isKeyValid(packet.getKey())) {
            throw new OpenR66ProtocolShutdownException("Shutdown Type received");
        }
        logger.error("Invalid Shutdown command");
        throw new OpenR66ProtocolBusinessException("Invalid Shutdown comand");
    }
}
