package toberumono.wrf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

import toberumono.namelist.parser.Namelist;
import toberumono.utils.files.BasicTransferActions;
import toberumono.utils.files.TransferFileWalker;
import toberumono.wrf.scope.AbstractScope;
import toberumono.wrf.scope.ModuleScopedMap;
import toberumono.wrf.scope.NamedScopeValue;
import toberumono.wrf.scope.ScopedMap;
import toberumono.wrf.scope.ScopedList;
import toberumono.wrf.timing.Timing;

import static toberumono.wrf.SimulationConstants.*;

public abstract class Module extends AbstractScope<Simulation> {
	protected final Logger logger;
	private final String name;
	private final ScopedMap parameters, module;
	private Timing timing;
	private Path namelistPath;
	private Namelist namelist;
	private ScopedList dependencies;
	
	public Module(ModuleScopedMap parameters, Simulation sim) {
		super(sim);
		this.parameters = parameters;
		this.parameters.setParent(this);
		namelistPath = null;
		namelist = null;
		timing = null;
		dependencies = null;
		name = (String) parameters.get("name");
		logger = Logger.getLogger(SIMULATION_LOGGER_ROOT + ".module." + getName());
		module = (ScopedMap) parameters.get("module");
	}
	
	protected Timing parseTiming(ScopedMap timing) {
		return WRFRunnerComponentFactory.generateComponent(Timing.class, timing, getSim().getTiming());
	}
	
	protected Namelist ingestNamelist() throws IOException {
		if (getNamelistPath() != null && getSim().getSourcePath(getName()) != null)
			return new Namelist(getSim().getSourcePath(getName()).resolve(getNamelistPath()));
		return null;
	}
	
	protected void writeNamelist() throws IOException {
		if (getNamelist() != null && getSim().getActivePath(getName()) != null)
			getNamelist().write(getSim().getActivePath(getName()).resolve(getNamelistPath()));
	}
	
	public abstract void updateNamelist() throws IOException, InterruptedException;
	
	public abstract void execute() throws IOException, InterruptedException;
	
	public abstract void cleanUp() throws IOException, InterruptedException;
	
	public Path getNamelistPath() { //namelistPath holds the location of the namelist file relative to the module's root directory
		if (namelistPath == null)
			namelistPath = (module.containsKey(NAMELIST_FIELD_NAME) ? Paths.get((String) module.get(NAMELIST_FIELD_NAME)) : null);
		return namelistPath;
	}
	
	@NamedScopeValue("timing")
	public Timing getTiming() {
		if (timing != null) //First one is to avoid unnecessary use of synchronization
			return timing;
		synchronized (name) {
			if (timing != null)
				return timing;
			return timing = parseTiming((ScopedMap) parameters.get("timing"));
		}
	}
	
	public ScopedList getDependencies() {
		if (dependencies != null) //First one is to avoid unnecessary use of synchronization
			return dependencies;
		synchronized (name) {
			if (dependencies != null)
				return dependencies;
			ScopedList dependencies = new ScopedList(this);
			if (module.containsKey("dependencies")) {
				Object deps = module.get("dependencies");
				if (deps instanceof String)
					dependencies.add(getSim().getModule((String) deps));
				else if (deps instanceof ScopedList)
					((ScopedList) deps).stream().map(o -> o instanceof String ? (String) o : o.toString()).map(getSim()::getModule).forEach(dependencies::add);
			}
			this.dependencies = dependencies;
		}
		return dependencies;
	}
	
	public String getName() {
		return name;
	}
	
	public Simulation getSim() {
		return getParent();
	}
	
	public Namelist getNamelist() throws IOException {
		if (namelist != null) //First one is to avoid unnecessary use of synchronization
			return namelist;
		synchronized (name) {
			if (namelist != null)
				return namelist;
			return namelist = ingestNamelist();
		}
	}

	@NamedScopeValue("parameters")
	public ScopedMap getParameters() {
		return parameters;
	}
	
	/**
	 * Performs the operation used to link the working directories back to the source installation.<br>
	 * This uses {@link BasicTransferActions#SYMLINK}.
	 * 
	 * @throws IOException
	 *             if an error occured while creating the links.
	 */
	public void linkToWorkingDirectory() throws IOException {
		Files.createDirectories(getSim().getActivePath(getName()));
		if (getSim().getSourcePath(getName()) != null)
			//We don't need anything from the src directories, so we exclude them.
			Files.walkFileTree(getSim().getSourcePath(getName()), new TransferFileWalker(getSim().getActivePath(getName()), BasicTransferActions.SYMLINK,
					p -> !filenameTest(p.getFileName().toString()), p -> !p.getFileName().toString().equals("src"), null, logger, false));
	}
	
	/**
	 * Tests the filename for patterns that indicate that the file should be excluded from the linking operation.<br>
	 * Basically, we want to minimize the number of links we're creating.
	 * 
	 * @param filename
	 *            the filename to test
	 * @return {@code true} if the name matches one of the patterns
	 */
	public static boolean filenameTest(String filename) {
		filename = filename.toLowerCase();
		String extension = filename.substring(filename.lastIndexOf('.') + 1);
		if (extension.equals("csh"))
			return false;
		if (filename.startsWith("wrf") && filename.indexOf('.') == -1) //This eliminates all wrfbdy, wrfin, wrfout, wrfrst files.
			return true;
		if (filename.startsWith("rsl.out") || filename.startsWith("rsl.error"))
			return true;
		return filename.startsWith("namelist") || filename.startsWith("readme") || extension.charAt(0) == 'f' || extension.charAt(0) == 'c' || extension.equals("log");
	}
}
