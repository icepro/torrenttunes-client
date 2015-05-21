package com.torrenttunes.client;

import static com.torrenttunes.client.db.Tables.LIBRARY;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.musicbrainz.mp3.tagger.Tools.Song;
import com.torrenttunes.client.db.Tables.Library;
import com.turn.ttorrent.common.Torrent;

public class ScanDirectory {

	static final Logger log = LoggerFactory.getLogger(ScanDirectory.class);

	public File dir;
	public TorrentClient torrentClient;

	public static ScanDirectory start(File dir, TorrentClient torrentClient) {
		return new ScanDirectory(dir, torrentClient);
	}

	private ScanDirectory(File dir, TorrentClient torrentClient) {
		this.dir = dir;
		this.torrentClient = torrentClient;

		scan();
	}

	private void scan() {

		// List all the music files in the sub or sub directories
		String[] types = {"mp3"};

		Collection<File> files = FileUtils.listFiles(dir, types , true);

		// Remove all that aren't already in the library(you don't need to upload or seed them)
		Set<File> torrentDBFiles = loadTorrentsFromDB();

		log.info("New torrent files: " + files);


		// Use ScanInfo to keep track of operations and messages while you're doing them
		Set<ScanInfo> scanInfos = new LinkedHashSet<ScanInfo>();

		for (File file : files) {
			scanInfos.add(ScanInfo.create(file));
		}

		// The main scanning loop
		for (ScanInfo si : scanInfos) {

			try {
				
				// Fetch the song MBID
				si.setStatus(ScanStatus.Scanning);
				si.setStatus(ScanStatus.FetchingMusicBrainzId);
				Song song = Song.fetchSong(si.getFile());
				si.setMbid(song.getRecordingMBID());
				

				// Create a torrent for the file, put it in the /.app/torrents dir
				si.setStatus(ScanStatus.CreatingTorrent);
				File torrentFile = createAndSaveTorrent(si, song);
				
				// If that file already exists in the DB, you don't need to do anything to it
				if (torrentDBFiles.contains(torrentFile)) {
					log.info(torrentFile + " was already in the DB");
					continue;
				}
				
				// Upload the torrent to the tracker
				si.setStatus(ScanStatus.UploadingTorrent);
				Tools.uploadFileToTracker(torrentFile);
				
				// Start seeding it
				si.setStatus(ScanStatus.Completed);
				torrentClient.addTorrent(si.getFile().getParentFile(), torrentFile);
				
				// Save it to the DB
				Tools.dbInit();
				Library library = LIBRARY.create("mbid", song.getRecordingMBID(),
						"torrent_path", torrentFile.getAbsolutePath(),
						"output_parent_path", si.getFile().getParentFile().getAbsolutePath());
				library.saveIt();
				Tools.dbClose();
				
				
				
				



			} 

			// Couldn't find the song
			catch (NoSuchElementException | InterruptedException | IOException e) {
				e.printStackTrace();
				si.setStatus(ScanStatus.MusicBrainzError);
				continue;
			}


		}

		log.info("Done scanning");

	}

	private static Set<File> loadTorrentsFromDB() {
		Tools.dbInit();
		List<Library> library = LIBRARY.findAll();
		library.isEmpty();
		Set<File> libraryFiles = new HashSet<File>();
		for (Library track : library) {
			libraryFiles.add(new File(track.getString("torrent_path")));
		}
		Tools.dbClose();
		
		return libraryFiles;
	}



	private File createAndSaveTorrent(ScanInfo si, Song song)
			throws InterruptedException, IOException, FileNotFoundException {
		String torrentFileName = Tools.constructTrackTorrentFilename(
				si.getFile(), song.getRecordingMBID());
		File torrentFile = new File(DataSources.TORRENTS_DIR() + "/" + torrentFileName + ".torrent");
		

		List<List<URI>> announceList = Arrays.asList(DataSources.ANNOUNCE_LIST());


		Torrent torrent = Torrent.create(si.getFile().getParentFile(), 
				Arrays.asList(si.getFile()), 
				Torrent.DEFAULT_PIECE_LENGTH,
				announceList, 
				System.getProperty("user.name"));
		
		OutputStream os = new FileOutputStream(torrentFile);
		torrent.save(os);
		os.close();
		return torrentFile;
	}



	/**
	 * An enum list of states and messages while scanning
	 * @author tyler
	 *
	 */
	public enum ScanStatus {
		Pending(" "), 
		Scanning("Scanning"), 
		FetchingMusicBrainzId("Found MusicBrainz ID (MBID)"), 
		MusicBrainzError("Couldn't Find MusicBrainz ID(MBID)"),
		CreatingTorrent("Creating a torrent file"),
		UploadingTorrent("Uploading torrent file to server"),
		UploadingError("Couldn't upload the torrent file"),
		Completed("Completed, and seeding file");


		private String s;

		ScanStatus(String s) {
			this.s = s;
		}
		@Override 
		public String toString() { return s; }
	}

	public static class ScanInfo {
		private File file;

		private ScanStatus status;
		private String mbid;

		public static ScanInfo create(File file) {
			return new ScanInfo(file);
		}
		private ScanInfo(File file) {
			this.file = file;
			this.status = ScanStatus.Pending;
		}
		public File getFile() {
			return file;
		}
		public ScanStatus getStatus() {
			return status;
		}
		public void setStatus(ScanStatus status) {
			log.debug("Status for " + file.getName() + " : " + status.toString());
			this.status = status;
		}
		public String getMbid() {
			return mbid;
		}
		public void setMbid(String mbid) {
			this.mbid = mbid;
		}




	}



}
