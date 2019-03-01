package prophet4j;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import prophet4j.meta.FeatureStruct;
import prophet4j.meta.FeatureStruct.FeatureManager;
import prophet4j.util.FeatureLearner;
import prophet4j.util.CodeDiffer;
import tech.sourced.siva.IndexEntry;
import tech.sourced.siva.SivaReader;

// https://github.com/src-d/datasets/tree/master/PublicGitArchive/pga
// https://pga.sourced.tech
// https://stedolan.github.io/jq/manual/
// https://github.com/src-d/siva-java
// https://github.com/eclipse/jgit
// https://github.com/centic9/jgit-cookbook
// check whether it should be similar to BenchProgram.cpp
public class Demo {
    // pga list -u github.com/src-d/ -f json | jq -r 'select(.fileCount > 100) | .sivaFilenames[]' | pga get -i -o ~
    // pga list -l java -f json | jq -r 'select(.commitsCount > 10000) | select(.commitsCount < 10100) | select(.langsFilesCount[.langs | index("Java")]==(.langsFilesCount | max)) | .sivaFilenames[]' | pga get -i -o ~
    /*
    pga list -l java -f json | jq -r 'select(.commitsCount > 10000) | select(.commitsCount < 10100) | select(.langsFilesCount[.langs | index("Java")]==(.langsFilesCount | max)) | .url, .commitsCount, .sivaFilenames'
    https://github.com/swagger-api/swagger-codegen
    10021
    [
      "d8c0f69ad42c803d2363cfcfb7d138aa3933be61.siva"
    ]
    https://github.com/pegasus-isi/pegasus
    10032
    [
      "327b2cd10cd27b8872ab59b4ab2f51c94113aea5.siva",
      "701b812e8ccff5c4d0ae2023818bdff573e3d09c.siva",
      "873c2c47d8752d22d908da1425925c5653bdb95a.siva",
      "8f1051d4d0926510ad3f629628e7d57140919fff.siva",
      "eab5465f9662eab52c93e8834ab66f17a570e638.siva"
    ]
    https://github.com/camunda/camunda-bpm-platform
    10022
    [
      "b5ec00079c4bb1b4eb5e55c3a590a76fde5ac477.siva"
    ]
    https://github.com/apache/logging-log4j2
    10036
    [
      "10fb9656a916d1c0ff57c28d7dcbfcb5bd313278.siva"
    ]
     */
    private final String CARDUMEN_DATA_DIR = "src/main/resources/prophet4j/cardumen-data/";
    private final String CARDUMEN_TEST_DIR = "src/main/resources/prophet4j/cardumen-test/";
    private final String CARDUMEN_CSV_DIR = "src/main/resources/prophet4j/cardumen-csv/";
    private final String CARDUMEN_VECTORS_DIR = "src/main/resources/prophet4j/cardumen-vectors/";
    private final String SIVA_FILES_DIR = "src/main/resources/prophet4j/siva-files/";
    private final String SIVA_UNPACKED_DIR = "src/main/resources/prophet4j/siva-unpacked/";
    private final String SIVA_COMMITS_DIR = "src/main/resources/prophet4j/siva-commits/";
    private final String SIVA_VECTORS_DIR = "src/main/resources/prophet4j/siva-vectors/";
    private static final Logger logger = LogManager.getLogger(Demo.class.getName());

    private void unpack() {
        logger.log(Level.INFO, "unpacking siva files");
        try {
            String sampleFile = SIVA_FILES_DIR + "10fb9656a916d1c0ff57c28d7dcbfcb5bd313278.siva";
            SivaReader sivaReader = new SivaReader(new File(sampleFile));
            List<IndexEntry> index = sivaReader.getIndex().getFilteredIndex().getEntries();
            for (IndexEntry indexEntry : index) {
                InputStream entry = sivaReader.getEntry(indexEntry);
                Path outPath = Paths.get(SIVA_UNPACKED_DIR.concat(indexEntry.getName()));
                FileUtils.copyInputStreamToFile(entry, new File(outPath.toString()));
            }
        } catch (Exception ex) {
            logger.log(Level.ERROR, ex.toString(), ex);
        }
    }

    private DiffEntry diffFile(Repository repo, String oldCommit,
                       String newCommit, String path) throws IOException, GitAPIException {
//        Config config = new Config();
//        config.setBoolean("diff", null, "renames", true);
//        DiffConfig diffConfig = config.get(DiffConfig.KEY);
        Git git = new Git(repo);
        List<DiffEntry> diffList = git.diff().
                setOldTree(prepareTreeParser(repo, oldCommit)).
                setNewTree(prepareTreeParser(repo, newCommit)).
//                setPathFilter(FollowFilter.create(path, diffConfig)).
        call();
        if (diffList.size() == 0)
            return null;
        if (diffList.size() > 1)
            throw new RuntimeException("invalid diff");
        return diffList.get(0);
    }

    private AbstractTreeIterator prepareTreeParser(Repository repository, String objectId) throws IOException {
        // from the commit we can build the tree which allows us to construct the TreeParser
        //noinspection Duplicates
        RevWalk walk = new RevWalk(repository);
        RevCommit commit = walk.parseCommit(repository.resolve(objectId));
        RevTree tree = walk.parseTree(commit.getTree().getId());

        CanonicalTreeParser treeParser = new CanonicalTreeParser();
        ObjectReader reader = repository.newObjectReader();
        treeParser.reset(reader, tree.getId());

        walk.dispose();

        return treeParser;
    }

    private void runDiff(Repository repo, String oldCommit, String newCommit, String path) throws IOException, GitAPIException {
        // Diff README.md between two commits. The file is named README.md in
        // the new commit (5a10bd6e), but was named "jgit-cookbook README.md" in
        // the old commit (2e1d65e4).
        DiffEntry diff = diffFile(repo, oldCommit, newCommit, path);

        // Display the diff
        System.out.println("Showing diff of " + path);
        DiffFormatter formatter = new DiffFormatter(System.out);
        formatter.setRepository(repo);
        //noinspection ConstantConditions
        formatter.format(diff);
    }

    private void listDiff(Repository repository, Git git, String oldCommit, String newCommit) throws GitAPIException, IOException {
        final List<DiffEntry> diffs = git.diff()
                .setOldTree(prepareTreeParser(repository, oldCommit))
                .setNewTree(prepareTreeParser(repository, newCommit))
                .call();

        System.out.println("Found: " + diffs.size() + " differences");
        for (DiffEntry diff : diffs) {
            System.out.println("Diff: " + diff.getChangeType() + ": " +
                    (diff.getOldPath().equals(diff.getNewPath()) ? diff.getNewPath() : diff.getOldPath() + " -> " + diff.getNewPath()));
        }
    }

    private CommitDiffer filterDiff(Repository repository, Git git, String oldCommitName, String newCommitName) throws GitAPIException, IOException {
        final List<DiffEntry> diffs = git.diff()
                .setOldTree(prepareTreeParser(repository, oldCommitName))
                .setNewTree(prepareTreeParser(repository, newCommitName))
                .call();

        CommitDiffer commitDiffer = new CommitDiffer();
        System.out.println("Found: " + diffs.size() + " differences");
        for (DiffEntry diff : diffs) {
            if (diff.getChangeType().equals(DiffEntry.ChangeType.MODIFY)) {
                String oldPath = diff.getOldPath();
                String newPath = diff.getNewPath();
                if (oldPath.endsWith(".java") && newPath.endsWith(".java")) {
                    // todo: excluded some commits when files are renamed, correct it later
                    if (oldPath.equals(newPath)) {
                        commitDiffer.addDiffer(oldCommitName, newCommitName, oldPath, newPath);
                    } else {
                        System.out.println("oldPath is different from newPath");
                    }
                }
            }
        }
        return commitDiffer;
    }

    private void obtainDiff(Repository repository, RevCommit commit, List<String> paths) throws IOException, GitAPIException {
        // and using commit's tree find the path
        RevTree tree = commit.getTree();
        System.out.println("Having tree: " + tree);

        // now try to find a specific file
        TreeWalk treeWalk = new TreeWalk(repository);
        treeWalk.addTree(tree);
        treeWalk.setRecursive(true);
        for (String path : paths) {
            String filePath = SIVA_COMMITS_DIR + commit.getName() + "/" + path;
            File file = new File(filePath);
            if (!file.exists()) {
                treeWalk.setFilter(PathFilter.create(path));
                if (!treeWalk.next()) {
                    throw new IllegalStateException("Did not find expected file '" + path + "'");
                }

                ObjectId objectId = treeWalk.getObjectId(0);
                ObjectLoader loader = repository.open(objectId);
                // and then one can the loader to read the file
//                loader.copyTo(System.out);
                loader.copyTo(FileUtils.openOutputStream(file));
            }
        }
    }

    private class Differ {
        String oldFilePath;
        String newFilePath;
        String vectorFilePath;

        Differ(String oldCommitName, String newCommitName, String oldPath, String newPath) {
            super();
            oldFilePath = SIVA_COMMITS_DIR + oldCommitName + "/" + oldPath;
            newFilePath = SIVA_COMMITS_DIR + newCommitName + "/" + newPath;
            vectorFilePath = SIVA_VECTORS_DIR + oldCommitName + "~" + newCommitName + "/" + newPath;
        }
    }

    private class CommitDiffer {
        List<Differ> differs = new ArrayList<>();
        Map<String, ArrayList<String>> paths = new HashMap<>();

        void addDiffer(String oldCommitName, String newCommitName, String oldPath, String newPath) {
            differs.add(new Differ(oldCommitName, newCommitName, oldPath, newPath));
            if (!paths.containsKey(oldCommitName)) {
                paths.put(oldCommitName, new ArrayList<>());
            }
            paths.get(oldCommitName).add(oldPath);
            if (!paths.containsKey(newCommitName)) {
                paths.put(newCommitName, new ArrayList<>());
            }
            paths.get(newCommitName).add(newPath);
        }

        ArrayList<String> getPaths(String commitName) {
            return paths.size() > 0 ? paths.get(commitName) : new ArrayList<>();
        }
    }

    private void handleCommits() throws IOException, GitAPIException {
        // if siva-unpacked files do not exist then uncommented the next line
        boolean existUnpackDir = new File(SIVA_UNPACKED_DIR).exists();
        boolean existCommitsDir = new File(SIVA_COMMITS_DIR).exists();
        if (!existUnpackDir) {
            unpack();
        }
        int progressAll, progressNow = 0;
        // prepare the whole data-set
        List<Differ> differs = new ArrayList<>();
        File repoDir = new File(SIVA_UNPACKED_DIR);
        // now open the resulting repository with a FileRepositoryBuilder
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Repository repository = builder.setGitDir(repoDir)
                .readEnvironment() // scan environment GIT_* variables
                .findGitDir() // scan up the file system tree
                .build();
        System.out.println("Having repository: " + repository.getDirectory());

        Git git = new Git(repository);
        Iterable<RevCommit> commits = git.log().all().call();

        int countCommits = 0;
        int countDiffers = 0;
        RevCommit lastCommit = null;
        for (RevCommit commit : commits) {
            System.out.println("LogCommit: " + commit);
            if (lastCommit != null) {
                // todo: why runDiff() for some commits returns "java.lang.RuntimeException: invalid diff"? (tested on the very first one case)
//                runDiff(repository, lastCommit.getName(), commit.getName(), "README.md");
//                listDiff(repository, git, lastCommit.getName(), commit.getName());
                CommitDiffer commitDiffer = filterDiff(repository, git, lastCommit.getName(), commit.getName());
                // obtain oldFile and newFile (save files to disk)
                if (!existCommitsDir) {
                    obtainDiff(repository, lastCommit, commitDiffer.getPaths(lastCommit.getName()));
                    obtainDiff(repository, commit, commitDiffer.getPaths(commit.getName()));
                }
                // add data into the whole data-set
                differs.addAll(commitDiffer.differs);
                countDiffers += commitDiffer.differs.size();
            }
            lastCommit = commit;
            countCommits++;
            // remove 3 lines below to genRepairCandidates on all commits (around 10k commits)
            if (countCommits >= 10) {
                break;
            }
            /* 10036 != 12813 why? I guess because "we store all references (including all pull requests) from different repositories that share the same initial commit – root" not sure ... todo: maybe create one issue someday
            https://github.com/apache/logging-log4j2
            10036["10fb9656a916d1c0ff57c28d7dcbfcb5bd313278.siva"]
            */
        }
        System.out.println(countCommits + " Commits");
        System.out.println(countDiffers + " Differs");
        progressAll = countDiffers;
//        runDiff(repository, "5fddbeb678bd2c36c5e5c891ab8f2b143ced5baf", "5d7303c49ac984a9fec60523f2d5297682e16646", "README.md");
        CodeDiffer codeDiffer = new CodeDiffer(true);
        List<String> filePaths = new ArrayList<>();
        for (Differ differ : differs) {
//            filePaths.add(differ.oldFilePath);
//            filePaths.add(differ.newFilePath);
            try {
                File vectorFile = new File(differ.vectorFilePath);
                if (!vectorFile.exists()) {
                    List<FeatureManager> featureManagers = codeDiffer.func4Demo(new File(differ.oldFilePath), new File(differ.newFilePath));
                    if (featureManagers.size() == 0) {
                        // diff.commonAncestor() returns null value
                        continue;
                    }
                    FeatureStruct.save(vectorFile, featureManagers);
                }
                filePaths.add(differ.vectorFilePath);
                progressNow += 1;
                System.out.println(progressNow + " / " + progressAll);
                System.out.println(differ.vectorFilePath);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        new FeatureLearner().func4Demo(filePaths);
        // clean up here to not keep using more and more disk-space for these samples
//        FileUtils.deleteDirectory(repoDir.getParentFile());
    }

    private Map<String, Map<File, List<File>>> loadCardumenData() throws NullPointerException {
        Map<String, Map<File, List<File>>> catalogs = new HashMap<>();
        // CARDUMEN_DATA_DIR CARDUMEN_TEST_DIR
        for (File typeFile : new File(CARDUMEN_DATA_DIR).listFiles((dir, name) -> !name.startsWith("."))) {
            for (File numFile : typeFile.listFiles((dir, name) -> !name.startsWith("."))) {
                String pathName = typeFile.getName() + numFile.getName();
                if (!catalogs.containsKey(pathName)) {
                    catalogs.put(pathName, new LinkedHashMap<>());
                }
                Map<File, List<File>> catalog = catalogs.get(pathName);
                File[] buggyFiles = null;
                File[] patchedFiles = null;
                for (File dataFile : numFile.listFiles((dir, name) -> !name.startsWith("."))) {
                    if (dataFile.getName().contains("buggy")) {
                        buggyFiles = dataFile.listFiles((dir, name) -> !name.startsWith("."));
                    } else if (dataFile.getName().contains("patched")) {
                        patchedFiles = dataFile.listFiles((dir, name) -> !name.startsWith("."));
                    }
                }
                List<File> keys = new ArrayList<>();
                List<File> values = new ArrayList<>();
                for (File buggyFile : buggyFiles) {
                    if (buggyFile.getName().endsWith(".java")) {
                        keys.add(buggyFile);
                    }
                }
                for (File patchedFile : patchedFiles) {
                    FilenameFilter filter = (dir, name) -> name.endsWith(".java");
                    values.addAll(Arrays.asList(patchedFile.listFiles(filter)));
                }
                for (File key : keys) {
                    String keyName = key.getName();
                    for (File value : values) {
                        String valueName = value.getName();
                        if (keyName.equals(valueName)) {
                            if (!catalog.containsKey(key)) {
                                catalog.put(key, new ArrayList<>());
                            }
                            catalog.get(key).add(value);
                        }
                    }
                }
            }
        }
        return catalogs;
    }

    // patches: kth-tcs/overfitting-analysis(/data/Training/patched_cardumen/)
    private void handleData() throws NullPointerException {
        List<String> filePaths = new ArrayList<>();
        CodeDiffer codeDiffer = new CodeDiffer(true);
        Map<String, Map<File, List<File>>> catalogs = loadCardumenData();
        for (String pathName : catalogs.keySet()) {
            int progressAll = catalogs.size(), progressNow = 0;
            Map<File, List<File>> catalog = catalogs.get(pathName);
            for (File oldFile : catalog.keySet()) {
                for (File newFile : catalog.get(oldFile)) {
                    try {
                        String vectorFilePath = CARDUMEN_VECTORS_DIR + oldFile.getName() + "/" + newFile.getParentFile().getName();
                        System.out.println(vectorFilePath);
                        File vectorFile = new File(vectorFilePath);
                        if (!vectorFile.exists()) {
                            List<FeatureManager> featureManagers = codeDiffer.func4Demo(oldFile, newFile);
                            if (featureManagers.size() == 0) {
                                // diff.commonAncestor() returns null value
                                continue;
                            }
                            FeatureStruct.save(vectorFile, featureManagers);
                        }
                        filePaths.add(vectorFilePath);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
                progressNow += 1;
                System.out.println(pathName + " : " + progressNow + " / " + progressAll);
            }
        }
        new FeatureLearner().func4Demo(filePaths);
    }

    // patches: kth-tcs/overfitting-analysis(/data/Training/patched_cardumen/)
    private void generateCSV() throws NullPointerException {
        // if only one large csv file is needed, we could just combine all generated ones
        CodeDiffer codeDiffer = new CodeDiffer(false);
        Map<String, Map<File, List<File>>> catalogs = loadCardumenData();
        for (String pathName : catalogs.keySet()) {
            Map<String, List<FeatureManager>> metadata = new LinkedHashMap<>();
            Map<File, List<File>> catalog = catalogs.get(pathName);
            int progressAll = catalog.size(), progressNow = 0;
            String csvFileName = CARDUMEN_CSV_DIR + pathName + ".csv";
            File csvFile = new File(csvFileName);
            if (!csvFile.exists()) {
                for (File oldFile : catalog.keySet()) {
                    for (File newFile : catalog.get(oldFile)) {
                        try {
                            String buggyFileName = oldFile.getName(); // xxx.java
                            String patchedFileName = newFile.getParentFile().getName(); // patchX
                            String entryName = pathName + "-" + buggyFileName + "-" + patchedFileName;
                            System.out.print(entryName);
                            List<FeatureManager> featureManagers = codeDiffer.func4Demo(oldFile, newFile);
                            if (featureManagers.size() == 0) {
                                // diff.commonAncestor() returns null value
                                System.out.println("patched file in patched_cardumen/ does not match patch file in cardumen/");
                            }
                            metadata.put(entryName, featureManagers);
                            System.out.println("\tokay");
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                    progressNow += 1;
                    System.out.println(pathName + " : " + progressNow + " / " + progressAll);
                }
                // generate csv file
                FeatureStruct.generateCSV(csvFileName, metadata);
                System.out.println("csv generated");
            } else {
                System.out.println("csv existed");
            }
        }
    }

    public static void main(String[] args) {
        try {
            Demo demo = new Demo();
            // real commits from Git files (how to filter out functional changes from revision changes?)
//             demo.handleCommits();
            // handle ideal patches such as patches from kth-tcs/overfitting-analysis
//            demo.handleData();
            // generate .csv files for HeYE
            demo.generateCSV();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // todo: run featureLearner.java on HeYE's patches
    // todo: draw graphs on 1k commits for Martin
    // todo: the plan for integrating Coming and Prophet4J
    /*
    To be able to select different feature sets, eg
    ./coming -f prophet4j:sketch4repair foo.git
    ./coming -f prophet4j foo.git

    To be able to output the learned probability model:
    ./coming --output-prob-model prob.json -f prophet4j foo.git

    And then one would be able to predict the likelihood of a new patch
    ./prophet-predictor --prob-model prob.json --patch bar.patch
     */
}
