package com.impossibl.postgres.protocol.v30;

import static com.impossibl.postgres.protocol.TransactionStatus.Active;
import static com.impossibl.postgres.protocol.TransactionStatus.Failed;
import static com.impossibl.postgres.protocol.TransactionStatus.Idle;
import static com.impossibl.postgres.utils.ChannelBuffers.readCString;
import static com.impossibl.postgres.utils.ChannelBuffers.writeCString;
import static java.util.Arrays.asList;
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.SEVERE;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;

import com.impossibl.postgres.protocol.BindExecCommand;
import com.impossibl.postgres.protocol.CloseCommand;
import com.impossibl.postgres.protocol.Command;
import com.impossibl.postgres.protocol.FunctionCallCommand;
import com.impossibl.postgres.protocol.Notice;
import com.impossibl.postgres.protocol.PrepareCommand;
import com.impossibl.postgres.protocol.Protocol;
import com.impossibl.postgres.protocol.QueryCommand;
import com.impossibl.postgres.protocol.ResultField;
import com.impossibl.postgres.protocol.ResultField.Format;
import com.impossibl.postgres.protocol.ServerObjectType;
import com.impossibl.postgres.protocol.StartupCommand;
import com.impossibl.postgres.protocol.TransactionStatus;
import com.impossibl.postgres.protocol.TypeRef;
import com.impossibl.postgres.system.BasicContext;
import com.impossibl.postgres.system.Context;
import com.impossibl.postgres.types.Registry;
import com.impossibl.postgres.types.Type;



public class ProtocolImpl implements Protocol {

	private static Logger logger = Logger.getLogger(ProtocolImpl.class.getName());

	// Frontend messages
	private static final byte PASSWORD_MSG_ID = 'p';
	private static final byte FLUSH_MSG_ID = 'H';
	private static final byte TERMINATE_MSG_ID = 'X';
	private static final byte SYNC_MSG_ID = 'S';
	private static final byte QUERY_MSG_ID = 'Q';
	private static final byte PARSE_MSG_ID = 'P';
	private static final byte BIND_MSG_ID = 'B';
	private static final byte DESCRIBE_MSG_ID = 'D';
	private static final byte EXECUTE_MSG_ID = 'E';
	private static final byte CLOSE_MSG_ID = 'C';
	private static final byte FUNCTION_CALL_MSG_ID = 'F';

	// Backend messages
	private static final byte BACKEND_KEY_MSG_ID = 'K';
	private static final byte AUTHENTICATION_MSG_ID = 'R';
	private static final byte ERROR_MSG_ID = 'E';
	private static final byte NOTICE_MSG_ID = 'N';
	private static final byte NOTIFICATION_MSG_ID = 'A';
	private static final byte COMMAND_COMPLETE_MSG_ID = 'C';
	private static final byte PARAMETER_STATUS_MSG_ID = 'S';
	private static final byte READY_FOR_QUERY_MSG_ID = 'Z';
	private static final byte PARAMETER_DESC_MSG_ID = 't';
	private static final byte ROW_DESC_MSG_ID = 'T';
	private static final byte ROW_DATA_MSG_ID = 'D';
	private static final byte PORTAL_SUSPENDED_MSG_ID = 's';
	private static final byte NO_DATA_MSG_ID = 'n';
	private static final byte EMPTY_QUERY_MSG_ID = 'I';
	private static final byte PARSE_COMPLETE_MSG_ID = '1';
	private static final byte BIND_COMPLETE_MSG_ID = '2';
	private static final byte CLOSE_COMPLETE_MSG_ID = '3';
	private static final byte FUNCTION_RESULT_MSG_ID = 'V';

	ProtocolShared.Ref sharedRef;
	Channel channel;
	BasicContext context;
	TransactionStatus txStatus;
	ProtocolListener listener;

	public ProtocolImpl(ProtocolShared.Ref sharedRef, Channel channel, BasicContext context) {
		this.sharedRef = sharedRef;
		this.channel = channel;
		this.context = context;
		this.txStatus = Idle;
	}
	
	public Context getContext() {
		return context;
	}
	
	@Override
	public void shutdown() {
		
		try {
			ChannelBuffer msg = ChannelBuffers.dynamicBuffer();
			writeTerminate(msg);
			channel.write(msg).addListener(ChannelFutureListener.CLOSE);
		}
		catch(Exception e) {
			//Close anyway...
			channel.close().awaitUninterruptibly(100);
		}
		
		sharedRef.release();
	}
	
	void setListener(ProtocolListener listener) {
		this.listener = listener;
	}
	
	@Override
	public StartupCommand createStartup(Map<String, Object> settings) {
		return new StartupCommandImpl(settings);
	}

	@Override
	public PrepareCommand createPrepare(String statementName, String sqlText, List<Type> parameterTypes) {
		return new PrepareCommandImpl(statementName, sqlText, parameterTypes);
	}

	@Override
	public BindExecCommand createBindExec(String portalName, String statementName, List<Type> parameterTypes, List<Object> parameterValues, List<ResultField> resultFields, Class<?> rowType) {
		return new BindExecCommandImpl(portalName, statementName, parameterTypes, parameterValues, resultFields, rowType);
	}

	@Override
	public QueryCommand createQuery(String sqlText) {
		return new QueryCommandImpl(sqlText);
	}

	@Override
	public FunctionCallCommand createFunctionCall(String functionName, List<Type> parameterTypes, List<Object> parameterValues) {
		return new FunctionCallCommandImpl(functionName, parameterTypes, parameterValues);
	}

	@Override
	public CloseCommand createClose(ServerObjectType objectType, String objectName) {
		return new CloseCommandImpl(objectType, objectName);
	}

	public synchronized void execute(Command cmd) throws IOException {
		
		if(cmd instanceof CommandImpl == false)
			throw new IllegalArgumentException();
		
		try {
			
			((CommandImpl)cmd).execute(this);
			
		}
		finally {
			
			//Ensure listener is reset
			listener = null;
		}
	}

	@Override
	public TransactionStatus getTransactionStatus() {
		return txStatus;
	}

	public void writeStartup(ChannelBuffer msg, Map<String, Object> params) throws IOException {

		if(logger.isLoggable(FINEST))
			logger.finest("STARTUP: " + params);
			
		beginMessage(msg, (byte) 0);

		// Version
		msg.writeShort(3);
		msg.writeShort(0);

		// Name=Value pairs
		for (Map.Entry<String, Object> paramEntry : params.entrySet()) {
			writeCString(msg, paramEntry.getKey(), context.getCharset());
			writeCString(msg, paramEntry.getValue().toString(), context.getCharset());
		}

		msg.writeByte(0);

		endMessage(msg);
	}
	
	public void writePassword(ChannelBuffer msg, String password) throws IOException {

		if(logger.isLoggable(FINEST))
			logger.finest("PASSWORD: " + password);
			
		beginMessage(msg, PASSWORD_MSG_ID);

		writeCString(msg, password, context.getCharset());

		endMessage(msg);
	}

	public void writeQuery(ChannelBuffer msg, String query) throws IOException {

		if(logger.isLoggable(FINEST))
			logger.finest("QUERY: " + query);
			
		beginMessage(msg, QUERY_MSG_ID);

		writeCString(msg, query, context.getCharset());

		endMessage(msg);
	}

	public void writeParse(ChannelBuffer msg, String stmtName, String query, List<Type> paramTypes) throws IOException {
		
		if(logger.isLoggable(FINEST))
			logger.finest("PARSE (" + stmtName + "): " + query);

		beginMessage(msg, PARSE_MSG_ID);

		writeCString(msg, stmtName != null ? stmtName : "", context.getCharset());
		writeCString(msg, query, context.getCharset());

		msg.writeShort(paramTypes.size());
		for (Type paramType : paramTypes) {
			int paramTypeOid = paramType != null ? paramType.getId() : 0;
			msg.writeInt(paramTypeOid);
		}

		endMessage(msg);
	}

	public void writeBind(ChannelBuffer msg, String portalName, String stmtName, List<Type> parameterTypes, List<Object> parameterValues, List<Format> resultFieldFormats) throws IOException {

		if(logger.isLoggable(FINEST))
			logger.finest("BIND (" + portalName + "): " + parameterValues.size());

		beginMessage(msg, BIND_MSG_ID);

		writeCString(msg, portalName != null ? portalName : "", context.getCharset());
		writeCString(msg, stmtName != null ? stmtName : "", context.getCharset());

		loadParams(msg, parameterTypes, parameterValues);

		//Set format for results fields
		if(resultFieldFormats.isEmpty()) {
			//Request all binary
			msg.writeShort(1);
			msg.writeShort(1);
		}
		else {
			//Select result format for each
			msg.writeShort(resultFieldFormats.size());
			for(Format format : resultFieldFormats) {
				msg.writeShort(format.ordinal());
			}
		}

		endMessage(msg);
	}

	public void writeDescribe(ChannelBuffer msg, ServerObjectType target, String targetName) throws IOException {

		if(logger.isLoggable(FINEST))
			logger.finest("DESCRIBE " + target + " (" + targetName + ")");

		beginMessage(msg, DESCRIBE_MSG_ID);

		msg.writeByte(target.getId());
		writeCString(msg, targetName != null ? targetName : "", context.getCharset());

		endMessage(msg);
	}

	public void writeExecute(ChannelBuffer msg, String portalName, int maxRows) throws IOException {

		if(logger.isLoggable(FINEST))
			logger.finest("EXECUTE (" + portalName + "): " + maxRows);

		beginMessage(msg, EXECUTE_MSG_ID);

		writeCString(msg, portalName != null ? portalName : "", context.getCharset());
		msg.writeInt(maxRows);

		endMessage(msg);
	}

	public void writeFunctionCall(ChannelBuffer msg, int functionId, List<Type> paramTypes, List<Object> paramValues) throws IOException {

		beginMessage(msg, FUNCTION_CALL_MSG_ID);

		msg.writeInt(functionId);

		loadParams(msg, paramTypes, paramValues);

		msg.writeShort(1);

		endMessage(msg);
	}

	public void writeClose(ChannelBuffer msg, ServerObjectType target, String targetName) throws IOException {

		if(logger.isLoggable(FINEST))
			logger.finest("CLOSE " + target + ": " + targetName);
			
		beginMessage(msg, CLOSE_MSG_ID);

		msg.writeByte(target.getId());
		writeCString(msg, targetName != null ? targetName : "", context.getCharset());

		endMessage(msg);
	}

	public void writeFlush(ChannelBuffer msg) throws IOException {
		
		if(logger.isLoggable(FINEST))
			logger.finest("FLUSH");
			
		writeMessage(msg, FLUSH_MSG_ID);
	}

	public void writeSync(ChannelBuffer msg) throws IOException {

		if(logger.isLoggable(FINEST))
			logger.finest("SYNC");
			
		writeMessage(msg, SYNC_MSG_ID);
	}

	public void writeTerminate(ChannelBuffer msg) throws IOException {

		if(logger.isLoggable(FINEST))
			logger.finest("TERM");
			
		writeMessage(msg, TERMINATE_MSG_ID);
	}
	
	public void send(ChannelBuffer msg) throws IOException {
		channel.write(msg);
	}

	protected void loadParams(ChannelBuffer buffer, List<Type> paramTypes, List<Object> paramValues) throws IOException {

		// Select format for parameters
		if(paramTypes == null) {
			buffer.writeShort(1);
			buffer.writeShort(1);
		}
		else {
			buffer.writeShort(paramTypes.size());
			for(Type paramType : paramTypes) {
				buffer.writeShort(paramType.getParameterFormat().ordinal());
			}
		}

		// Values for each parameter
		if (paramTypes == null) {
			buffer.writeShort(0);
		}
		else {
			buffer.writeShort(paramTypes.size());
			for (int c = 0; c < paramTypes.size(); ++c) {

				Type paramType = paramTypes.get(c);
				Object paramValue = paramValues.get(c);

				Type.Codec codec = paramType.getCodec(paramType.getParameterFormat());
				codec.encoder.encode(paramType, buffer, paramValue, context);
				
			}
		}
	}

	protected void writeMessage(ChannelBuffer msg, byte msgId) throws IOException {

		msg.writeByte(msgId);
		msg.writeInt(4);
	}

	protected void beginMessage(ChannelBuffer msg, byte msgId) {
		
		if(msg == null) {
			throw new IllegalArgumentException("Parent message required");
		}
		
		if(msgId != 0)
			msg.writeByte(msgId);

		msg.markWriterIndex();

		msg.writeInt(-1);
	}

	protected void endMessage(ChannelBuffer msg) throws IOException {

		int endPos = msg.writerIndex();
		
		msg.resetWriterIndex();
		
		int begPos = msg.writerIndex();
		
		msg.setInt(begPos, endPos - begPos);
		
		msg.writerIndex(endPos);
	}

	/*
	 * 
	 * Message dispatching & parsing
	 */

	public void dispatch(ResponseMessage msg) throws IOException {

		switch (msg.id) {
		case AUTHENTICATION_MSG_ID:
			receiveAuthentication(msg.data);
			break;

		case BACKEND_KEY_MSG_ID:
			receiveBackendKeyData(msg.data);
			break;

		case PARAMETER_DESC_MSG_ID:
			receiveParameterDescriptions(msg.data);
			break;

		case ROW_DESC_MSG_ID:
			receiveRowDescription(msg.data);
			break;

		case ROW_DATA_MSG_ID:
			receiveRowData(msg.data);
			break;

		case PORTAL_SUSPENDED_MSG_ID:
			receivePortalSuspended(msg.data);
			break;

		case NO_DATA_MSG_ID:
			receiveNoData(msg.data);
			break;

		case PARSE_COMPLETE_MSG_ID:
			receiveParseComplete(msg.data);
			break;

		case BIND_COMPLETE_MSG_ID:
			receiveBindComplete(msg.data);
			break;

		case CLOSE_COMPLETE_MSG_ID:
			receiveCloseComplete(msg.data);
			break;

		case EMPTY_QUERY_MSG_ID:
			receiveEmptyQuery(msg.data);
			break;

		case FUNCTION_RESULT_MSG_ID:
			receiveFunctionResult(msg.data);
			break;

		case ERROR_MSG_ID:
			receiveError(msg.data);
			break;

		case NOTICE_MSG_ID:
			receiveNotice(msg.data);
			break;

		case NOTIFICATION_MSG_ID:
			receiveNotification(msg.data);
			break;

		case COMMAND_COMPLETE_MSG_ID:
			receiveCommandComplete(msg.data);
			break;

		case PARAMETER_STATUS_MSG_ID:
			receiveParameterStatus(msg.data);
			break;

		case READY_FOR_QUERY_MSG_ID:
			receiveReadyForQuery(msg.data);
			break;

		default:
				logger.fine("unsupported message type: " + (msg.id & 0xff));
		}

	}

	public void dispatchException(Throwable cause) throws IOException {
		
		logger.log(SEVERE, "Error dispatching message", cause);
		
		if(listener != null) {
			listener.error(new Notice("EXCEPTION", Notice.CONNECTION_EXC_CLASS, cause.getMessage()));
		}
	}

	private void receiveAuthentication(ChannelBuffer buffer) throws IOException {

		int code = buffer.readInt();
		switch (code) {
		case 0:

			// Ok
			listener.authenticated(this);
			return;

		case 2:

			// KerberosV5
			listener.authenticateKerberos(this);
			break;

		case 3:

			// Cleartext
			listener.authenticateClear(this);
			return;

		case 4:

			// Crypt
			listener.authenticateCrypt(this);
			return;

		case 5:

			// MD5
			byte[] salt = new byte[4];
			buffer.readBytes(salt);

			listener.authenticateMD5(this, salt);

			return;

		case 6:

			// SCM Credential
			listener.authenticateSCM(this);
			break;

		case 7:

			// GSS
			listener.authenticateGSS(this);
			break;

		case 8:

			// GSS Continue
			listener.authenticateGSSCont(this);
			break;

		case 9:

			// SSPI
			listener.authenticateSSPI(this);
			break;

		default:
			throw new UnsupportedOperationException("invalid authentication type");
		}
	}

	private void receiveBackendKeyData(ChannelBuffer buffer) throws IOException {

		int processId = buffer.readInt();
		int secretKey = buffer.readInt();

		listener.backendKeyData(processId, secretKey);
	}

	private void receiveError(ChannelBuffer buffer) throws IOException {

		Notice notice = parseNotice(buffer);

		logger.finest("ERROR: " + notice.getCode() + ": " + notice.getMessage());

		listener.error(notice);
	}

	private void receiveNotice(ChannelBuffer buffer) throws IOException {

		Notice notice = parseNotice(buffer);
		
		logger.finest(notice.getSeverity() + ": " + notice.getCode() + ": " + notice.getMessage());

		listener.notice(notice);
	}

	private void receiveParameterDescriptions(ChannelBuffer buffer) throws IOException {

		short paramCount = buffer.readShort();

		TypeRef[] paramTypes = new TypeRef[paramCount];

		for (int c = 0; c < paramCount; ++c) {

			int paramTypeId = buffer.readInt();
			paramTypes[c] = TypeRef.from(paramTypeId, context.getRegistry());
		}

		logger.finest("PARAM-DESC: " + paramCount);

		listener.parametersDescription(asList(paramTypes));
	}

	private void receiveRowDescription(ChannelBuffer buffer) throws IOException {

		Registry registry = context.getRegistry();
		
		short fieldCount = buffer.readShort();

		ResultField[] fields = new ResultField[fieldCount];

		for (int c = 0; c < fieldCount; ++c) {

			ResultField field = new ResultField();
			field.name = readCString(buffer, context.getCharset());
			field.relationId = buffer.readInt();
			field.relationAttributeNumber = buffer.readShort();
			field.typeRef = TypeRef.from(buffer.readInt(), registry);
			field.typeLength = buffer.readShort();
			field.typeModifier = buffer.readInt();
			field.format = ResultField.Format.values()[buffer.readShort()];

			fields[c] = field;
		}

		logger.finest("ROW-DESC: " + fieldCount);

		listener.rowDescription(asList(fields));
	}

	private void receiveRowData(ChannelBuffer buffer) throws IOException {
		logger.finest("DATA");
		listener.rowData(buffer);
	}

	private void receivePortalSuspended(ChannelBuffer buffer) throws IOException {
		logger.finest("SUSPEND");
		listener.portalSuspended();
	}

	private void receiveNoData(ChannelBuffer buffer) throws IOException {
		logger.finest("NO-DATA");
		listener.noData();
	}

	private void receiveCloseComplete(ChannelBuffer buffer) throws IOException {
		logger.finest("CLOSE-COMP");
		listener.closeComplete();
	}

	private void receiveBindComplete(ChannelBuffer buffer) throws IOException {
		logger.finest("BIND-COMP");
		listener.bindComplete();
	}

	private void receiveParseComplete(ChannelBuffer buffer) throws IOException {
		logger.finest("PARSE-COMP");
		listener.parseComplete();
	}

	private void receiveEmptyQuery(ChannelBuffer buffer) throws IOException {
		logger.finest("EMPTY");
		listener.emptyQuery();
	}

	private void receiveFunctionResult(ChannelBuffer buffer) throws IOException {

		logger.finest("FUNCTION-RES");

		listener.functionResult(buffer);
	}

	private void receiveCommandComplete(ChannelBuffer buffer) throws IOException {

		String commandTag = readCString(buffer, context.getCharset());

		String[] parts = commandTag.split(" ");

		String command = parts[0];
		Long rowsAffected = null;
		Long oid = null;

		switch (command) {

		case "INSERT":

			if (parts.length == 3) {

				oid = Long.parseLong(parts[1]);
				rowsAffected = Long.parseLong(parts[2]);
			}
			else {
				throw new IOException("error parsing command tag");
			}

			break;

		case "SELECT":
			
			if (parts.length == 2) {

				rowsAffected = null;
			}
			else {
				throw new IOException("error parsing command tag");
			}

			break;
			
		case "UPDATE":
		case "DELETE":
		case "MOVE":
		case "FETCH":

			if (parts.length == 2) {

				rowsAffected = Long.parseLong(parts[1]);
			}
			else {
				throw new IOException("error parsing command tag");
			}

			break;

		case "COPY":

			if (parts.length == 1) {

				// Nothing to parse but accepted
			}
			else if (parts.length == 2) {

				rowsAffected = Long.parseLong(parts[1]);
			}
			else {
				throw new IOException("error parsing command tag");
			}

			break;
			
		case "CREATE":
		case "DROP":
		case "ALTER":
			
			if(parts.length == 2) {

				command += " " + parts[1];
				rowsAffected = 0l;
			}
			else {
				throw new IOException("error parsing command tag");
			}
			
			break;

		default:
			
			if(parts.length != 1)
				throw new IOException("error parsing command tag");
			
			rowsAffected = 0l;
		}

		logger.finest("COMPLETE: " + commandTag);

		listener.commandComplete(command, rowsAffected, oid);
	}

	protected void receiveNotification(ChannelBuffer buffer) throws IOException {

		int processId = buffer.readInt();
		String channelName = readCString(buffer, context.getCharset());
		String payload = readCString(buffer, context.getCharset());

		logger.finest("NOTIFY: " + processId + " - " + channelName + " - " + payload);

		listener.notification(processId, channelName, payload);
	}

	private void receiveParameterStatus(ChannelBuffer buffer) throws IOException {

		String name = readCString(buffer, context.getCharset());
		String value = readCString(buffer, context.getCharset());

		context.updateSystemParameter(name, value);
	}

	private void receiveReadyForQuery(ChannelBuffer buffer) throws IOException {

		switch(buffer.readByte()) {
		case 'T':
			txStatus = Active;
			break;
		case 'E':
			txStatus = Failed;
			break;
		case 'I':
			txStatus = Idle;
			break;
		default:
			throw new IllegalStateException("invalid transaction status");
		}

		logger.finest("READY: " + txStatus);
		
		if(listener != null)
			listener.ready(txStatus);
	}

	private Notice parseNotice(ChannelBuffer buffer) {

		Notice notice = new Notice();

		byte msgId;

		while ((msgId = buffer.readByte()) != 0) {

			switch (msgId) {
			case 'S':
				notice.setSeverity(readCString(buffer, context.getCharset()));
				break;

			case 'C':
				notice.setCode(readCString(buffer, context.getCharset()));
				break;

			case 'M':
				notice.setMessage(readCString(buffer, context.getCharset()));
				break;

			case 'D':
				notice.setDetail(readCString(buffer, context.getCharset()));
				break;

			case 'H':
				notice.setHint(readCString(buffer, context.getCharset()));
				break;

			case 'P':
				notice.setPosition(readCString(buffer, context.getCharset()));
				break;
				
			case 'W':
				notice.setWhere(readCString(buffer, context.getCharset()));
				break;

			case 'F':
				notice.setFile(readCString(buffer, context.getCharset()));
				break;

			case 'L':
				notice.setLine(readCString(buffer, context.getCharset()));
				break;

			case 'R':
				notice.setRoutine(readCString(buffer, context.getCharset()));
				break;

			default:
				// Read and ignore
				readCString(buffer, context.getCharset());
				break;
			}

		}
		
		return notice;
	}

}
