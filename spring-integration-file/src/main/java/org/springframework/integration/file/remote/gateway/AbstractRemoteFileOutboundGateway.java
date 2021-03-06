/*
 * Copyright 2002-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.file.remote.gateway;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.integration.Message;
import org.springframework.integration.MessagingException;
import org.springframework.integration.expression.ExpressionUtils;
import org.springframework.integration.file.FileHeaders;
import org.springframework.integration.file.filters.FileListFilter;
import org.springframework.integration.file.remote.AbstractFileInfo;
import org.springframework.integration.file.remote.RemoteFileUtils;
import org.springframework.integration.file.remote.session.Session;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.handler.AbstractReplyProducingMessageHandler;
import org.springframework.integration.handler.ExpressionEvaluatingMessageProcessor;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Base class for Outbound Gateways that perform remote file operations.
 *
 * @author Gary Russell
 * @author Artem Bilan
 * @since 2.1
 */
public abstract class AbstractRemoteFileOutboundGateway<F> extends AbstractReplyProducingMessageHandler {

	protected final SessionFactory<F> sessionFactory;

	protected final Command command;

	/**
	 * Enumeration of commands supported by the gateways.
	 */
	public static enum Command {

		/**
		 * List remote files.
		 */
		LS("ls"),

		/**
		 * Retrieve a remote file.
		 */
		GET("get"),

		/**
		 * Remove a remote file (path - including wildcards).
		 */
		RM("rm"),

		/**
		 * Retrieve multiple files matching a wildcard path.
		 */
		MGET("mget"),

		/**
		 * Move (rename) a remote file.
		 */
		MV("mv");

		private String command;

		private Command(String command) {
			this.command = command;
		}

		public String getCommand() {
			return this.command;
		}

		public static Command toCommand(String cmd) {
			for (Command command : values()) {
				if (command.getCommand().equals(cmd)) {
					return command;
				}
			}
			throw new IllegalArgumentException("No Command with value '" + cmd + "'");
		}
	}

	/**
	 * Enumeration of options supported by various commands.
	 *
	 */
	public static enum Option {

		/**
		 * Don't return full file information; just the name (ls).
		 */
		NAME_ONLY("-1"),

		/**
		 * Include files beginning with {@code .}, including directories {@code .} and {@code ..} in the results (ls).
		 */
		ALL("-a"),

		/**
		 * Do not sort the results (ls with NAME_ONLY).
		 */
		NOSORT("-f"),

		/**
		 * Include directories in the results (ls).
		 */
		SUBDIRS("-dirs"),

		/**
		 * Include links in the results (ls).
		 */
		LINKS("-links"),

		/**
		 * Preserve the server timestamp (get, mget).
		 */
		PRESERVE_TIMESTAMP("-P"),

		/**
		 * Throw an exception if no files returned (mget).
		 */
		EXCEPTION_WHEN_EMPTY("-x"),

		/**
		 * Recursive (ls, mget)
		 */
		RECURSIVE("-R");

		private String option;

		private Option(String option) {
			this.option = option;
		}

		public String getOption() {
			return this.option;
		}

		public static Option toOption(String opt) {
			for (Option option : values()) {
				if (option.getOption().equals(opt)) {
					return option;
				}
			}
			throw new IllegalArgumentException("No option with value '" + opt + "'");
		}
	}

	private final ExpressionEvaluatingMessageProcessor<String> fileNameProcessor;

	private volatile ExpressionEvaluatingMessageProcessor<String> renameProcessor =
			new ExpressionEvaluatingMessageProcessor<String>(
					new SpelExpressionParser().parseExpression("headers." + FileHeaders.RENAME_TO));

	protected volatile Set<Option> options = new HashSet<Option>();

	private volatile String remoteFileSeparator = "/";

	private volatile Expression localDirectoryExpression;

	private volatile boolean autoCreateLocalDirectory = true;

	private volatile String temporaryFileSuffix = ".writing";

	/**
	 * An {@link FileListFilter} that runs against the <em>remote</em> file system view.
	 */
	private volatile FileListFilter<F> filter;


	private volatile Expression localFilenameGeneratorExpression;

	public AbstractRemoteFileOutboundGateway(SessionFactory<F> sessionFactory, String command,
			String expression) {
		this.sessionFactory = sessionFactory;
		this.command = Command.toCommand(command);
		this.fileNameProcessor = new ExpressionEvaluatingMessageProcessor<String>(
			new SpelExpressionParser().parseExpression(expression));
	}

	public AbstractRemoteFileOutboundGateway(SessionFactory<F> sessionFactory, Command command,
			String expression) {
		this.sessionFactory = sessionFactory;
		this.command = command;
		this.fileNameProcessor = new ExpressionEvaluatingMessageProcessor<String>(
			new SpelExpressionParser().parseExpression(expression));
	}


	/**
	 * @param options the options to set
	 */
	public void setOptions(String options) {
		String[] opts = options.split("\\s");
		for (String opt : opts) {
			String trimmedOpt = opt.trim();
			if (StringUtils.hasLength(trimmedOpt)) {
				this.options.add(Option.toOption(trimmedOpt));
			}
		}
	}

	/**
	 * @param remoteFileSeparator the remoteFileSeparator to set
	 */
	public void setRemoteFileSeparator(String remoteFileSeparator) {
		this.remoteFileSeparator = remoteFileSeparator;
	}

	/**
	 * @param localDirectory the localDirectory to set
	 */
	public void setLocalDirectory(File localDirectory) {
		if (localDirectory != null) {
			this.localDirectoryExpression = new LiteralExpression(localDirectory.getAbsolutePath());
		}
	}

	public void setLocalDirectoryExpression(Expression localDirectoryExpression) {
		this.localDirectoryExpression = localDirectoryExpression;
	}

	/**
	 * @param autoCreateLocalDirectory the autoCreateLocalDirectory to set
	 */
	public void setAutoCreateLocalDirectory(boolean autoCreateLocalDirectory) {
		this.autoCreateLocalDirectory = autoCreateLocalDirectory;
	}

	/**
	 * @param temporaryFileSuffix the temporaryFileSuffix to set
	 */
	public void setTemporaryFileSuffix(String temporaryFileSuffix) {
		this.temporaryFileSuffix = temporaryFileSuffix;
	}

	/**
	 * @param filter the filter to set
	 */
	public void setFilter(FileListFilter<F> filter) {
		this.filter = filter;
	}

	public void setRenameExpression(String expression) {
		Assert.notNull(expression, "'expression' cannot be null");
		this.renameProcessor = new ExpressionEvaluatingMessageProcessor<String>(
				new SpelExpressionParser().parseExpression(expression));
	}

	public void setLocalFilenameGeneratorExpression(Expression localFilenameGeneratorExpression) {
		Assert.notNull(localFilenameGeneratorExpression, "'localFilenameGeneratorExpression' must not be null");
		this.localFilenameGeneratorExpression = localFilenameGeneratorExpression;
	}


	@Override
	protected void doInit() {
		Assert.notNull(this.command, "command must not be null");
		if (Command.RM.equals(this.command) ||
				Command.GET.equals(this.command)) {
			Assert.isNull(this.filter, "Filters are not supported with the rm and get commands");
		}
		if (Command.GET.equals(this.command)
				|| Command.MGET.equals(this.command)) {
			Assert.notNull(this.localDirectoryExpression, "localDirectory must not be null");
			if (this.localDirectoryExpression instanceof LiteralExpression) {
				File localDirectory = new File(this.localDirectoryExpression.getExpressionString());
				try {
					if (!localDirectory.exists()) {
						if (this.autoCreateLocalDirectory) {
							if (logger.isDebugEnabled()) {
								logger.debug("The '" + localDirectory + "' directory doesn't exist; Will create.");
							}
							if (!localDirectory.mkdirs()) {
								throw new IOException("Failed to make local directory: " + localDirectory);
							}
						}
						else {
							throw new FileNotFoundException(localDirectory.getName());
						}
					}
				}
				catch (RuntimeException e) {
					throw e;
				}
				catch (Exception e) {
					throw new MessagingException(
							"Failure during initialization of: " + this.getComponentType(), e);
				}
			}
		}
		if (Command.MGET.equals(this.command)) {
			Assert.isTrue(!(this.options.contains(Option.SUBDIRS)),
					"Cannot use " + Option.SUBDIRS.toString() + " when using 'mget' use " + Option.RECURSIVE.toString() +
							" to obtain files in subdirectories");
		}
		if (this.getBeanFactory() != null) {
			this.fileNameProcessor.setBeanFactory(this.getBeanFactory());
			this.renameProcessor.setBeanFactory(this.getBeanFactory());
		}
	}

	@Override
	protected Object handleRequestMessage(Message<?> requestMessage) {
		Session<F> session = this.sessionFactory.getSession();
		try {
			switch (this.command) {
			case LS:
				return doLs(requestMessage, session);
			case GET:
				return doGet(requestMessage, session);
			case MGET:
				return doMget(requestMessage, session);
			case RM:
				return doRm(requestMessage, session);
			case MV:
				return doMv(requestMessage, session);
			default:
				return null;
			}
		}
		catch (IOException e) {
			throw new MessagingException(requestMessage, e);
		}
		finally {
			session.close();
		}
	}

	private Object doLs(Message<?> requestMessage, Session<F> session) throws IOException {
		String dir = this.fileNameProcessor.processMessage(requestMessage);
		if (!dir.endsWith(this.remoteFileSeparator)) {
			dir += this.remoteFileSeparator;
		}
		List<?> payload = ls(session, dir);
		return MessageBuilder.withPayload(payload)
			.setHeader(FileHeaders.REMOTE_DIRECTORY, dir)
			.build();
	}

	private Object doGet(Message<?> requestMessage, Session<F> session) throws IOException {
		String remoteFilePath =  this.fileNameProcessor.processMessage(requestMessage);
		String remoteFilename = this.getRemoteFilename(remoteFilePath);
		String remoteDir = this.getRemoteDirectory(remoteFilePath, remoteFilename);
		File payload = this.get(requestMessage, session, remoteDir, remoteFilePath, remoteFilename, true);
		return MessageBuilder.withPayload(payload)
			.setHeader(FileHeaders.REMOTE_DIRECTORY, remoteDir)
			.setHeader(FileHeaders.REMOTE_FILE, remoteFilename)
			.build();
	}

	private Object doMget(Message<?> requestMessage, Session<F> session) throws IOException {
		String remoteFilePath =  this.fileNameProcessor.processMessage(requestMessage);
		String remoteFilename = this.getRemoteFilename(remoteFilePath);
		String remoteDir = this.getRemoteDirectory(remoteFilePath, remoteFilename);
		List<File> payload = this.mGet(requestMessage, session, remoteDir, remoteFilename);
		return MessageBuilder.withPayload(payload)
			.setHeader(FileHeaders.REMOTE_DIRECTORY, remoteDir)
			.setHeader(FileHeaders.REMOTE_FILE, remoteFilename)
			.build();
	}

	private Object doRm(Message<?> requestMessage, Session<F> session) throws IOException {
		String remoteFilePath =  this.fileNameProcessor.processMessage(requestMessage);
		String remoteFilename = this.getRemoteFilename(remoteFilePath);
		String remoteDir = this.getRemoteDirectory(remoteFilePath, remoteFilename);
		boolean payload = this.rm(session, remoteFilePath);
		return MessageBuilder.withPayload(payload)
			.setHeader(FileHeaders.REMOTE_DIRECTORY, remoteDir)
			.setHeader(FileHeaders.REMOTE_FILE, remoteFilename)
			.build();
	}

	private Object doMv(Message<?> requestMessage, Session<F> session) throws IOException {
		String remoteFilePath =  this.fileNameProcessor.processMessage(requestMessage);
		String remoteFilename = this.getRemoteFilename(remoteFilePath);
		String remoteDir = this.getRemoteDirectory(remoteFilePath, remoteFilename);
		String remoteFileNewPath = this.renameProcessor.processMessage(requestMessage);
		Assert.hasLength(remoteFileNewPath, "New filename cannot be empty");

		this.mv(session, remoteFilePath, remoteFileNewPath);
		return MessageBuilder.withPayload(Boolean.TRUE)
			.setHeader(FileHeaders.REMOTE_DIRECTORY, remoteDir)
			.setHeader(FileHeaders.REMOTE_FILE, remoteFilename)
			.setHeader(FileHeaders.RENAME_TO, remoteFileNewPath)
			.build();
	}

	protected List<?> ls(Session<F> session, String dir) throws IOException {
		List<F> lsFiles = listFilesInRemoteDir(session, dir, "");
		if (!this.options.contains(Option.LINKS)) {
			purgeLinks(lsFiles);
		}
		if (!this.options.contains(Option.ALL)) {
			purgeDots(lsFiles);
		}
		if (this.options.contains(Option.NAME_ONLY)) {
			List<String> results = new ArrayList<String>();
			for (F file : lsFiles) {
				results.add(getFilename(file));
			}
			if (!this.options.contains(Option.NOSORT)) {
				Collections.sort(results);
			}
			return results;
		}
		else {
			List<AbstractFileInfo<F>> canonicalFiles = this.asFileInfoList(lsFiles);
			for (AbstractFileInfo<F> file : canonicalFiles) {
				file.setRemoteDirectory(dir);
			}
			if (!this.options.contains(Option.NOSORT)) {
				Collections.sort(canonicalFiles);
			}
			return canonicalFiles;
		}
	}

	private List<F> listFilesInRemoteDir(Session<F> session, String directory, String subDirectory) throws IOException {
		List<F> lsFiles = new ArrayList<F>();
		F[] files = session.list(directory + subDirectory);
		boolean recursion = this.options.contains(Option.RECURSIVE);
		if (!ObjectUtils.isEmpty(files)) {
			Collection<F> filteredFiles = this.filterFiles(files);
			for (F file : filteredFiles) {
				String fileName = this.getFilename(file);
				if (file != null) {
					if (this.options.contains(Option.SUBDIRS) || !this.isDirectory(file)) {
						if (recursion && StringUtils.hasText(subDirectory)) {
							lsFiles.add(enhanceNameWithSubDirectory(file, subDirectory));
						}
						else {
							lsFiles.add(file);
						}
					}
					if (recursion && this.isDirectory(file) && !(".".equals(fileName)) && !("..".equals(fileName))) {
						lsFiles.addAll(listFilesInRemoteDir(session, directory,  subDirectory + fileName + this.remoteFileSeparator));
					}
				}
			}
		}
		return lsFiles;
	}

	protected final List<F> filterFiles(F[] files) {
		return (this.filter != null) ? this.filter.filterFiles(files) : Arrays.asList(files);
	}

	protected void purgeLinks(List<F> lsFiles) {
		Iterator<F> iterator = lsFiles.iterator();
		while (iterator.hasNext()) {
			if (this.isLink(iterator.next())) {
				iterator.remove();
			}
		}
	}

	protected void purgeDots(List<F> lsFiles) {
		Iterator<F> iterator = lsFiles.iterator();
		while (iterator.hasNext()) {
			if (getFilename(iterator.next()).startsWith(".")) {
				iterator.remove();
			}
		}
	}

	/**
	 * Copy a remote file to the configured local directory.
	 *
	 *
	 * @param message
	 * @param session
	 * @param remoteDir
	 *@param remoteFilePath  @throws IOException
	 */
	protected File get(Message<?> message, Session<F> session, String remoteDir, String remoteFilePath, String remoteFilename, boolean lsFirst)
			throws IOException {
		F[] files = null;
		if (lsFirst) {
			files = session.list(remoteFilePath);
			if (files == null) {
				throw new MessagingException("Session returned null when listing " + remoteFilePath);
			}
			if (files.length != 1 || isDirectory(files[0]) || isLink(files[0])) {
				throw new MessagingException(remoteFilePath + " is not a file");
			}
		}
		File localFile = new File(this.generateLocalDirectory(message, remoteDir), this.generateLocalFileName(message, remoteFilename));
		if (!localFile.exists()) {
			String tempFileName = localFile.getAbsolutePath() + this.temporaryFileSuffix;
			File tempFile = new File(tempFileName);
			BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(tempFile));
			try {
				session.read(remoteFilePath, outputStream);
			}
			catch (Exception e) {
				if (e instanceof RuntimeException){
					throw (RuntimeException) e;
				}
				else {
					throw new MessagingException("Failure occurred while copying from remote to local directory", e);
				}
			}
			finally {
				try {
					outputStream.close();
				}
				catch (Exception ignored2) {
					//Ignore it
				}
			}
			if (!tempFile.renameTo(localFile)) {
				throw new MessagingException("Failed to rename local file");
			}
			if (lsFirst && this.options.contains(Option.PRESERVE_TIMESTAMP)) {
				localFile.setLastModified(getModified(files[0]));
			}
			return localFile;
		}
		else {
			throw new MessagingException("Local file " + localFile + " already exists");
		}
	}

	protected List<File> mGet(Message<?> message, Session<F> session, String remoteDirectory,
							  String remoteFilename) throws IOException {
		if (this.options.contains(Option.RECURSIVE)) {
			if (logger.isWarnEnabled() && !("*".equals(remoteFilename))) {
				logger.warn("File name pattern must be '*' when using recursion");
			}
			if (this.options.contains(Option.NAME_ONLY)) {
				this.options.remove(Option.NAME_ONLY);
			}
			return mGetWithRecursion(message, session, remoteDirectory, remoteFilename);
		}
		else {
			return mGetWithoutRecursion(message, session, remoteDirectory, remoteFilename);
		}
	}

	private List<File> mGetWithoutRecursion(Message<?> message, Session<F> session, String remoteDirectory,
			String remoteFilename) throws IOException {
		String path = this.generateFullPath(remoteDirectory, remoteFilename);
		String[] fileNames = session.listNames(path);
		if (fileNames == null) {
			fileNames = new String[0];
		}
		if (fileNames.length == 0 && this.options.contains(Option.EXCEPTION_WHEN_EMPTY)) {
			throw new MessagingException("No files found at " + remoteDirectory
					+ " with pattern " + remoteFilename);
		}
		List<File> files = new ArrayList<File>();
		for (String fileName : fileNames) {
			File file;
			if (fileName.contains(this.remoteFileSeparator) &&
					fileName.startsWith(remoteDirectory)) { // the server returned the full path
				file = this.get(message, session, remoteDirectory, fileName,
						fileName.substring(fileName.lastIndexOf(this.remoteFileSeparator)), false);
			}
			else {
				file = this.get(message, session, remoteDirectory,
						this.generateFullPath(remoteDirectory, fileName), fileName, false);
			}
			files.add(file);
		}
		return files;
	}

	private List<File> mGetWithRecursion(Message<?> message, Session<F> session, String remoteDirectory,
			String remoteFilename) throws IOException {
		List<File> files = new ArrayList<File>();
		@SuppressWarnings("unchecked")
		List<AbstractFileInfo<F>> fileNames = (List<AbstractFileInfo<F>>) this.ls(session, remoteDirectory);
		if (fileNames.size() == 0 && this.options.contains(Option.EXCEPTION_WHEN_EMPTY)) {
			throw new MessagingException("No files found at " + remoteDirectory
					+ " with pattern " + remoteFilename);
		}
		for (AbstractFileInfo<F> lsEntry : fileNames) {
			String fullFileName = remoteDirectory + this.getFilename(lsEntry);
			/*
			 * With recursion, the filename might contain subdirectory information
			 * normalize each file separately.
			 */
			String fileName = this.getRemoteFilename(fullFileName);
			String actualRemoteDirectory = this.getRemoteDirectory(fullFileName, fileName);
			File file = this.get(message, session, actualRemoteDirectory,
						fullFileName, fileName, false);
			files.add(file);
		}
		return files;
	}

	private String getRemoteDirectory(String remoteFilePath, String remoteFilename) {
		String remoteDir = remoteFilePath.substring(0, remoteFilePath.lastIndexOf(remoteFilename));
		if (remoteDir.length() == 0) {
			remoteDir = this.remoteFileSeparator;
		}
		return remoteDir;
	}

	private String generateFullPath(String remoteDirectory, String remoteFilename) {
		String path;
		if (this.remoteFileSeparator.equals(remoteDirectory)) {
			path = remoteFilename;
		}
		else if (remoteDirectory.endsWith(this.remoteFileSeparator)) {
			path = remoteDirectory + remoteFilename;
		}
		else {
			path = remoteDirectory + this.remoteFileSeparator + remoteFilename;
		}
		return path;
	}

	/**
	 * @param remoteFilePath
	 */
	protected String getRemoteFilename(String remoteFilePath) {
		String remoteFileName;
		int index = remoteFilePath.lastIndexOf(this.remoteFileSeparator);
		if (index < 0) {
			remoteFileName = remoteFilePath;
		}
		else {
			remoteFileName = remoteFilePath.substring(index + 1);
		}
		return remoteFileName;
	}

	protected boolean rm(Session<?> session, String remoteFilePath)
			throws IOException {
		return session.remove(remoteFilePath);
	}

	protected void mv(Session<?> session, String remoteFilePath, String remoteFileNewPath) throws IOException {
		int lastSeparator = remoteFileNewPath.lastIndexOf(this.remoteFileSeparator);
		if (lastSeparator > 0) {
			String remoteFileDirectory = remoteFileNewPath.substring(0, lastSeparator + 1);
			RemoteFileUtils.makeDirectories(remoteFileDirectory, session, this.remoteFileSeparator, this.logger);
		}
		session.rename(remoteFilePath, remoteFileNewPath);
	}

	private File generateLocalDirectory(Message<?> message, String remoteDirectory) {
		EvaluationContext evaluationContext = ExpressionUtils.createStandardEvaluationContext(this.getBeanFactory());
		evaluationContext.setVariable("remoteDirectory", remoteDirectory);
		// TODO Change 'desiredResultType' as 'File.class' after fix of SPR-10953.
		File localDir = new File(this.localDirectoryExpression.getValue(evaluationContext, message, String.class));
		if (!localDir.exists()) {
			Assert.isTrue(localDir.mkdirs(), "Failed to make local directory: " + localDir);
		}
		return localDir;
	}

	private String generateLocalFileName(Message<?> message, String remoteFileName){
		if (this.localFilenameGeneratorExpression != null){
			EvaluationContext evaluationContext = ExpressionUtils.createStandardEvaluationContext(this.getBeanFactory());
			evaluationContext.setVariable("remoteFileName", remoteFileName);
			return this.localFilenameGeneratorExpression.getValue(evaluationContext, message, String.class);
		}
		return remoteFileName;
	}

	abstract protected boolean isDirectory(F file);

	abstract protected boolean isLink(F file);

	abstract protected String getFilename(F file);

	abstract protected String getFilename(AbstractFileInfo<F> file);

	abstract protected long getModified(F file);

	abstract protected List<AbstractFileInfo<F>> asFileInfoList(Collection<F> files);

	abstract protected F enhanceNameWithSubDirectory(F file, String directory);
}
