package storm.applications.spout;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import storm.applications.constants.BaseConstants.BaseConf;
import storm.applications.spout.parser.Parser;
import storm.applications.util.config.ClassLoaderUtils;
import storm.applications.util.io.FileUtils;
import storm.applications.util.stream.StreamValues;

/**
 * 
 * @author Maycon Viana Bordin <mayconbordin@gmail.com>
 */
public class FileSpout extends AbstractSpout {
    private static final Logger LOG = LoggerFactory.getLogger(FileSpout.class);
    
    protected Parser parser;
    protected File[] files;
    protected Scanner scanner;
    protected int curFileIndex = 0;
    protected int curLineIndex = 0;
    private boolean finished = false;
    
    protected int taskId;
    protected int numTasks;

    @Override
    public void initialize() {
        taskId   = context.getThisTaskIndex();//context.getThisTaskId();
        numTasks = config.getInt(getConfigKey(BaseConf.SPOUT_THREADS));
        
        String parserClass = config.getString(getConfigKey(BaseConf.SPOUT_PARSER));
        parser = (Parser) ClassLoaderUtils.newInstance(parserClass, "parser", LOG);
        parser.initialize(config);
        
        buildIndex();
        openNextFile();
    }
    
    protected void buildIndex() {
        String path = config.getString(getConfigKey(BaseConf.SPOUT_PATH));
        if (StringUtils.isBlank(path)) {
            LOG.error("The source path has not been set");
            throw new RuntimeException("The source path has to beeen set");
        }
        
        LOG.info("Source path: {}", path);
        
        File dir = new File(path);
        if (!dir.exists()) {
            LOG.error("The source path {} does not exists", path);
            throw new RuntimeException("The source path '" + path + "' does not exists");
        }
        
        if (dir.isDirectory()) {
            files = dir.listFiles();
        } else {
            files = new File[1];
            files[0] = dir;
        }
        
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                int res = f1.lastModified() < f2.lastModified() ? -1 : ( f1.lastModified() > f2.lastModified() ? 1 : 0);
                return res;
            }
        });
        
        LOG.info("Number of files to read: {}", files.length);
    }
    
    @Override
    public void nextTuple() {
        String value = readFile();
        
        if (value == null)
            return;
        
        List<StreamValues> tuples = parser.parse(value);
        
        if (tuples != null) {
            for (StreamValues values : tuples) {
                String msgId = String.format("%d%d", curFileIndex, curLineIndex);
                collector.emit(values.getStreamId(), values, msgId);
            }
        }
    }

    protected String readFile() {
        if (finished) return null;
        
        String record = null;
                
        if (scanner.hasNextLine()) {
            record = readLine();
        } else {
            if ((curFileIndex+1) < files.length) {
                openNextFile();
                if (scanner.hasNextLine()) {
                     record = readLine();
                }				 
            } else {
                LOG.info("No more files to read");
                finished = true;
            }
        }
        
        return record;
    }
    
    /**
     * Read one line from the currently open file. If there's only one file, each
     * instance of the spout will read only a portion of the file.
     * @return The line
     */
    protected String readLine() {
        if (files.length == 1) {
            while (scanner.hasNextLine() && ++curLineIndex % numTasks != taskId)
                scanner.nextLine();
        }
        
        if (scanner.hasNextLine())
            return scanner.nextLine();
        else
            return null;
    }

    /**
     * Opens the next file from the index. If there's multiple instances of the
     * spout, it will read only a portion of the files.
     */
    protected void openNextFile() {
        while ((curFileIndex+1) % numTasks != taskId) {
            curFileIndex++;
        }

        if (curFileIndex < files.length) {
            try {
                File file = files[curFileIndex];
                scanner = new Scanner(file);
                curLineIndex = 0;
                
                LOG.info("Opened file {}, size {}", file.getName(), FileUtils.humanReadableByteCount(file.length()));
            } catch (FileNotFoundException ex) {
                LOG.error(String.format("File %s not found", files[curFileIndex]), ex);
                throw new IllegalStateException("File not found", ex);
            }
        }
    }
}	
