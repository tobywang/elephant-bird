package com.twitter.elephantbird.lucene;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.util.ToolRunner;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.twitter.elephantbird.mapreduce.input.LuceneHdfsDirectory;
import com.twitter.elephantbird.mapreduce.output.LuceneIndexOutputFormat;
import com.twitter.elephantbird.util.ExecuteOnClusterTool;
import com.twitter.elephantbird.util.HadoopUtils;
import com.twitter.elephantbird.util.HdfsUtils;

/**
 * Merges indexes that are stored in {@link LuceneHdfsDirectory}s
 *
 * @author Alex Levenson
 */
public class HdfsMergeTool extends ExecuteOnClusterTool {
  private static final Logger LOG = LoggerFactory.getLogger(HdfsMergeTool.class);

  private static final String OUTPUT_KEY = HdfsMergeTool.class.getName() + ".output";
  private static final String INDEXES_KEY = HdfsMergeTool.class.getName() + ".indexes";
  private static final String MAX_MERGE_FACTOR_KEY = HdfsMergeTool.class.getName()
      + ".maxMergeFactor";
  private static final String USAGE_MESSAGE = "HdfsMergeTool usage: <output location> "
      + "<max merge factor> "
      + "<index dir path glob> "
      + "<optional second index dir path glob>\n"
      + "example: /my/dir/index-merged /my/indexes/index-* /my/other/indexes/index-7";

  @Override
  protected void setup(String[] args, Configuration conf) throws IOException {
    Preconditions.checkArgument(args.length >=3, USAGE_MESSAGE);
    conf.set(OUTPUT_KEY, args[0]);
    conf.setInt(MAX_MERGE_FACTOR_KEY, Integer.valueOf(args[1]));
    List<Path> indexes = HdfsUtils.expandGlobs(Arrays.asList(args).subList(2, args.length), conf);
    HadoopUtils.writeStringListToConfAsJson(
        INDEXES_KEY,
        Lists.transform(indexes, new HdfsUtils.PathToQualifiedString(conf)),
        conf);
  }

  @Override
  public void execute(Mapper.Context context) throws IOException {
    List<String> indexes = HadoopUtils.readStringListFromConfAsJson(INDEXES_KEY,
        context.getConfiguration());
    Path output = new Path(context.getConfiguration().get(OUTPUT_KEY));

    File tmpDirFile = Files.createTempDir();
    int maxMergeFactor = context.getConfiguration().getInt(MAX_MERGE_FACTOR_KEY, -1);
    Preconditions.checkArgument(maxMergeFactor > 0);

    IndexWriter writer = LuceneIndexOutputFormat.createIndexWriter(
        tmpDirFile,
        new LuceneIndexOutputFormat.NeverTokenizeAnalyzer(),
        maxMergeFactor);

    Directory[] dirs = new Directory[indexes.size()];
    int dir = 0;
    for (String index : indexes) {
      dirs[dir++] =  new LuceneHdfsDirectory(index, FileSystem.get(context.getConfiguration()));
    }

    LOG.info("Adding indexes: " + indexes);
    writer.addIndexes(dirs);

    LOG.info("Force mergeing...");
    writer.forceMerge(1);

    LOG.info("Closing writer...");
    writer.close();


    FileSystem fs = FileSystem.get(context.getConfiguration());
    LOG.info("Copying index to HDFS...");
    if (!FileUtil.copy(tmpDirFile, fs, output, true, context.getConfiguration())) {
      throw new IOException("Failed to copy local index to HDFS!");
    }

    LOG.info("Index written to: " + output);
  }

  public static void main(String[] args) throws Exception {
    int status = ToolRunner.run(new HdfsMergeTool(), args);
    if (status != 0) {
      throw new RuntimeException("Merge tool exited with code: " + status);
    }
  }
}
