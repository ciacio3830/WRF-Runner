package toberumono.wrf;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.Locale;

import toberumono.json.JSONObject;
import toberumono.json.JSONSystem;
import toberumono.namelist.parser.Namelist;
import toberumono.namelist.parser.NamelistInnerList;
import toberumono.namelist.parser.NamelistInnerMap;
import toberumono.namelist.parser.NamelistParser;
import toberumono.namelist.parser.NamelistType;
import toberumono.structures.tuples.Pair;
import toberumono.utils.files.RecursiveEraser;

public class Runner {
	protected JSONObject configuration, general, paths, commands, features, parallel;
	protected Path root, configurationFile;
	
	public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException {
		Runner runner = new Runner(Paths.get(Runner.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent(), Paths.get(args[0]));
		runner.runWRF();
	}
	
	public Runner(Path root, Path configurationFile) throws IOException {
		this.root = root;
		configurationFile = root.resolve(configurationFile);
		refreshConfiguration(configurationFile);
	}
	
	/**
	 * @return the {@link Path} to the current configuration file
	 */
	public Path getConfigurationFile() {
		return configurationFile;
	}
	
	/**
	 * Reloads the data from the current configuration file.
	 * 
	 * @throws IOException
	 *             if the configuration file cannot be read from disk
	 * @see #getConfigurationFile()
	 */
	public void refreshConfiguration() throws IOException {
		refreshConfiguration(configurationFile);
	}
	
	/**
	 * Load the configuration data from the given JSON file and stores it for subsequent calls to
	 * {@link #refreshConfiguration()}.
	 * 
	 * @param configurationFile
	 *            a {@link Path} to the configuration file (provided initially as a command-line argument)
	 * @throws IOException
	 *             if the file cannot be read from disk
	 */
	public void refreshConfiguration(Path configurationFile) throws IOException {
		this.configurationFile = configurationFile;
		configuration = (JSONObject) JSONSystem.loadJSON(root.resolve(configurationFile));
		paths = (JSONObject) configuration.get("paths");
		commands = (JSONObject) configuration.get("commands");
		general = (JSONObject) configuration.get("general");
		features = (JSONObject) general.get("features");
		parallel = (JSONObject) general.get("parallel");
	}
	
	/**
	 * Executes the steps needed to run wget, WPS, and then WRF. This method automatically calculates the appropriate start
	 * and end times of the simulation from the configuration and Namelist files, and downloads the boundary data
	 * accordingly.
	 * 
	 * @throws IOException
	 *             if the Namelist files could not be read
	 * @throws InterruptedException
	 *             if one of the processes gets interrupted
	 */
	public void runWRF() throws IOException, InterruptedException {
		Path wrfPath = Paths.get(((String) paths.get("wrf").value())).toAbsolutePath();
		Path wpsPath = Paths.get(((String) paths.get("wps").value())).toAbsolutePath();
		
		Path wrfNamelistPath = wrfPath.resolve("namelist.input");
		Namelist input = NamelistParser.parseNamelist(wrfNamelistPath);
		Path wpsNamelistPath = wpsPath.resolve("namelist.wps");
		Namelist wps = NamelistParser.parseNamelist(wpsNamelistPath);
		int doms = ((Number) input.get("domains").get("max_dom").get(0).getY()).intValue();
		
		TimeRange tr = new TimeRange(input, Calendar.getInstance(), (JSONObject) configuration.get("timing"));
		Path working = tr.makeWorkingFolder(Paths.get(((String) paths.get("working").value())).toAbsolutePath(), wrfPath.getParent(), wpsPath, (String) commands.get("bash").value());
		Path gribPath = working.resolve("grib");
		wrfPath = working.resolve("WRFV3").resolve("run");
		
		NamelistParser.writeNamelist(writeWRFOutputPath(tr.updateWRFNamelistTimeRange(input, doms), working.resolve("output")), wrfPath.resolve("namelist.input"));
		NamelistParser.writeNamelist(writeWPSPaths(tr.updateWPSNamelistTimeRange(wps, doms), wpsPath), (wpsPath = working.resolve("WPS")).resolve("namelist.wps"));
		
		if (((Boolean) features.get("wget").value()))
			runWGet(tr, gribPath, input);
		
		if (((Boolean) features.get("wps").value()))
			runWPS(wps, wpsPath, gribPath, tr);
		else if (((Boolean) features.get("cleanup").value()))
			wpsCleanup(wpsPath, gribPath);
		
		if (((Boolean) features.get("wrf").value()))
			runWRF(input, wrfPath, tr);
		else if (((Boolean) features.get("cleanup").value()))
			Files.walkFileTree(wrfPath.getParent(), new RecursiveEraser());
	}
	
	public static Namelist writeWPSPaths(Namelist wps, Path wpsPath) {
		NamelistInnerList geogList = wps.get("geogrid").get("geog_data_path");
		Path newPath = wpsPath.resolve(geogList.get(0).getY().toString());
		geogList.set(0, new Pair<>(NamelistType.String, newPath.toAbsolutePath().normalize().toString()));
		NamelistInnerList metList = wps.get("metgrid").get("opt_output_from_metgrid_path");
		newPath = wpsPath.resolve(metList.get(0).getY().toString());
		metList.set(0, new Pair<>(NamelistType.String, newPath.toAbsolutePath().normalize().toString()));
		return wps;
	}
	
	public static Namelist writeWRFOutputPath(Namelist input, Path output) {
		NamelistInnerMap tc = input.get("time_control");
		NamelistInnerList outpath = new NamelistInnerList();
		String dir = output.toAbsolutePath().normalize().toString();
		if (!dir.endsWith(System.getProperty("file.separator")))
			dir += System.getProperty("file.separator");
		outpath.add(new Pair<>(NamelistType.String, dir + "wrfout_d<domain>_<date>"));
		tc.put("history_outname", outpath);
		return input;
	}
	
	/**
	 * This downloads the necessary grib data for running the current simulation.<br>
	 * Override this method to change how grib data is downloaded (e.g. change the source, timing offset)
	 * 
	 * @param tr
	 *            {@link TimeRange} that represents the start and end times of the simulation
	 * @param input
	 *            the namelist.input file from the WRF directory
	 * @throws IOException
	 *             if an IO error occurs
	 * @throws InterruptedException
	 *             if one of the processes is interrupted
	 */
	public void runWGet(TimeRange tr, Path gribPath, Namelist input) throws IOException, InterruptedException {
		NamelistInnerMap tc = input.get("time_control");
		//We construct the duration in hours here and add the start hour so that we only need to do the addition for the offset once instead of max_dom times.
		int hoursDuration = ((Number) tc.get("run_days").get(0).getY()).intValue() + ((Number) tc.get("run_hours").get(0).getY()).intValue() + ((Number) tc.get("start_hour").get(0).getY()).intValue();
		if (((Number) tc.get("run_minutes").get(0).getY()).intValue() != 0 || ((Number) tc.get("run_seconds").get(0).getY()).intValue() != 0 || hoursDuration % 3 != 0)
			throw new RuntimeException("Run length must be such that when it is converted to hours, it is divisible by 3.");
		
		//Create the grib directory if it doesn't already exist and initialize a ProcessBuilder to use that directory 
		Files.createDirectories(gribPath);
		ProcessBuilder wgetPB = makePB(gribPath.toFile());
		Calendar start = tr.getX();
		//Construct the prefix of the url
		String url = "http://www.ftp.ncep.noaa.gov/data/nccf/com/nam/prod/nam.";
		url += String.format(Locale.US, "%d%02d%02d", start.get(Calendar.YEAR), start.get(Calendar.MONTH) + 1, start.get(Calendar.DAY_OF_MONTH)) + "/nam.t00z.awip3d";
		String suffix = ".tm00.grib2";
		//Download each datafile
		for (int i = ((Number) tc.get("start_hour").get(0).getY()).intValue(); i <= hoursDuration; i += 3) {
			String[] command = {(String) commands.get("wget").value(), "--no-check-certificate", "-N", url + String.format(Locale.US, "%02d", i) + suffix};
			System.out.println(runPB(wgetPB, command));
		}
	}
	
	/**
	 * This executes the commands needed to run a WPS process in sequence (ungrib and geogrid are run in parallel).
	 * 
	 * @param wps
	 *            the WPS {@link Namelist} file
	 * @param wpsPath
	 *            the {@link Path} to the root of the WPS installation
	 * @param tr
	 *            {@link TimeRange} that represents the start and end times of the simulation
	 * @throws IOException
	 *             if the WPS directory or any of the programs within it could not be opened or executed
	 * @throws InterruptedException
	 *             if one of the processes is interrupted
	 */
	public void runWPS(Namelist wps, Path wpsPath, Path gribPath, TimeRange tr) throws IOException, InterruptedException {
		ProcessBuilder wpsPB = makePB(wpsPath.toFile());
		gribPath = gribPath.toAbsolutePath().normalize();
		runPB(wpsPB, "./link_grib.csh", gribPath.toString() + "/");
		//Run ungrib and geogrid in parallel
		wpsPB.command("./ungrib.exe", "2>&1", "|", "tee", "./ungrib.log");
		Process ungrib = wpsPB.start();
		runPB(wpsPB, "./geogrid.exe", "2>&1", "|", "tee", "./geogrid.log");
		ungrib.waitFor();
		runPB(wpsPB, "./metgrid.exe", "2>&1", "|", "tee", "./metgrid.log");
		if (((Boolean) features.get("cleanup").value())) {
			if (wps.get("share").get("opt_output_from_geogrid_path") != null)
				runPB(wpsPB, (String) commands.get("bash").value(), "-c", "rm -f "
						+ wpsPath.resolve(((String) wps.get("share").get("opt_output_from_geogrid_path").get(0).getY())).resolve("geo_").toString() + "*");
			else
				runPB(wpsPB, (String) commands.get("bash").value(), "-c", "rm -f geo_*");
			wpsCleanup(wpsPath, gribPath);
		}
	}
	
	public void wpsCleanup(Path wpsPath, Path gribPath) throws IOException {
		RecursiveEraser re = new RecursiveEraser();
		Files.walkFileTree(wpsPath, re);
		Files.walkFileTree(gribPath, re);
	}
	
	/**
	 * This executes the commands needed to run a real-data WRF installation.
	 * 
	 * @param input
	 *            the WRF {@link Namelist} file
	 * @param wrfPath
	 *            the path to the <i>run</i> directory of the WRF installation
	 * @param tr
	 *            {@link TimeRange} that represents the start and end times of the simulation
	 * @throws IOException
	 *             if the WRF run directory or any of the programs within it could not be opened or executed
	 * @throws InterruptedException
	 *             if one of the processes is interrupted
	 */
	public void runWRF(Namelist input, Path wrfPath, TimeRange tr) throws IOException, InterruptedException {
		ProcessBuilder wrfPB = makePB(wrfPath.toFile());
		runPB(wrfPB, "./real.exe", "2>&1", "|", "tee", "./real.log");
		String[] wrfCommand = new String[0];
		//Calculate which command to use
		if ((Boolean) parallel.get("is-dmpar").value()) {
			if ((Boolean) parallel.get("boot-lam").value())
				wrfCommand = new String[]{"mpiexec", "-boot", "-np", parallel.get("processors").value().toString(), "./wrf.exe", "2>&1", "|", "tee", "./wrf.log"};
			else
				wrfCommand = new String[]{"mpiexec", "-np", parallel.get("processors").value().toString(), "./wrf.exe", "2>&1", "|", "tee", "./wrf.log"};
		}
		else
			wrfCommand = new String[]{"./wrf.exe", "2>&1", "|", "tee", "./wrf.log"};
		//If we are waiting for WRF, we can also clean up after it.
		if (((Boolean) general.get("wait-for-WRF").value())) {
			runPB(wrfPB, wrfCommand);
			if (((Boolean) features.get("cleanup").value()))
				Files.walkFileTree(wrfPath.getParent(), new RecursiveEraser());
		}
		else {
			wrfPB.command(wrfCommand);
			wrfPB.start();
		}
	}
	
	/**
	 * Starts a {@link Process} using the given {@link ProcessBuilder} and command and waits for its completion.
	 * 
	 * @param pb
	 *            the {@link ProcessBuilder} with which to execute the command
	 * @param command
	 *            the command to execute
	 * @return the exit value of the {@link Process} that started
	 * @throws IOException
	 *             if an IO error occurs
	 * @throws InterruptedException
	 *             if the {@link Process} was interrupted
	 */
	public static int runPB(ProcessBuilder pb, String... command) throws IOException, InterruptedException {
		pb.command(command);
		Process p = pb.start();
		return p.waitFor();
	}
	
	/**
	 * Performs the common steps for setting up the {@link ProcessBuilder ProcessBuilders} used in this system. (Basically
	 * just avoids some copy and paste)
	 * 
	 * @param directory
	 *            the working directory for the {@link ProcessBuilder}
	 * @return a {@link ProcessBuilder} with {@link Redirect#INHERIT} redirections and the given working directory
	 */
	public static ProcessBuilder makePB(File directory) {
		ProcessBuilder pb = new ProcessBuilder();
		pb.redirectError(Redirect.INHERIT);
		pb.redirectOutput(Redirect.INHERIT);
		pb.directory(directory);
		return pb;
	}
}
