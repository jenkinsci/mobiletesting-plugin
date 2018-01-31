package org.jenkinsci.plugins.mobilestudio;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jenkinsci.plugins.mobilestudio.Constants.LIST;
import static org.jenkinsci.plugins.mobilestudio.Constants.TELERIK_MOBILE_TESTING_RUNNER;
import static org.jenkinsci.plugins.mobilestudio.Constants.TEST;

@SuppressWarnings("unused")
public class MobileStudioTestBuilder extends Builder implements SimpleBuildStep, Serializable {

    private static final long serialVersionUID = 43887870234990L;

    private String mobileStudioRunnerPath;
    private String msgServer;
    private String deviceId;
    private String testPath;
    private TestType testType = TestType.SINGLE_TEST;
    private String outputFileName;
    private String projectRoot;
    private boolean testAsUnit;

    private boolean isWindows = false;


    @DataBoundConstructor
    public MobileStudioTestBuilder(String mobileStudioRunnerPath,
                       String msgServer,
                       String deviceId,
                       String projectRoot,
                       String testPath,
                       boolean testAsUnit) {
        this.mobileStudioRunnerPath = mobileStudioRunnerPath;
        this.testPath = testPath;
        this.msgServer = msgServer;
        this.deviceId = deviceId;
        if (testPath != null && testPath.endsWith(LIST)) {
            testType = TestType.TEST_LIST;
        }
        this.testAsUnit = testAsUnit;
        this.projectRoot = projectRoot;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {

        MyCallable task = new MyCallable(String.valueOf(workspace),
                            Constants.MOBILE_STUDIO_RESULTS_DIR,
                            this.mobileStudioRunnerPath,
                            this.testAsUnit,
                            this.msgServer,
                            this.projectRoot,
                            this.deviceId,
                            this.testType.toString(),
                            this.testPath);


        String result = launcher.getChannel().call(task);
        listener.getLogger().println(result);
        if (result != null && result.contains("> Step")){
            run.setResult(Result.SUCCESS);
        } else {
            run.setResult(Result.FAILURE);
        }
    }


    class MyCallable implements Callable<String, IOException> {
        private static final long serialVersionUID = 754832542381L;

        private String workspace;
        private String resultsDir;
        private String mobileStudioRunnerPath;
        private boolean testAsUnit;
        private boolean isWindows;
        private String msgServer;
        private String projectRoot;
        private String deviceId;
        private String testType;
        private String testPath;

        public MyCallable(String workspace,
                          String resultsDir,
                          String mobileStudioRunnerPath,
                          boolean testAsUnit,
                          String msgServer,
                          String projectRoot,
                          String deviceId,
                          String testType,
                          String testPath){
            this.workspace = workspace;
            this.resultsDir = resultsDir;
            this.testAsUnit = testAsUnit;
            this.mobileStudioRunnerPath = mobileStudioRunnerPath;
            this.msgServer = msgServer;
            this.projectRoot = projectRoot;
            this.deviceId = deviceId;
            this.testType = testType;
            this.testPath = testPath;
        }

        @Override
        public void checkRoles(RoleChecker roleChecker) throws SecurityException {

        }

        @Override
        public String call() throws IOException {

            String output = "\nRunning OS: Linux\n";

            if (System.getProperty("os.name").toLowerCase().contains("windows")){
                this.isWindows = true;
                output = "\nRunning OS: Windows\n";
            }

            String outputFileName = "MobileStudioResults-" + System.currentTimeMillis() + ".xml";
            String command = buildCommand(this.workspace, outputFileName);
            output += "\nCommand: \n" + command + "\n";

            Runtime rt = Runtime.getRuntime();
            Process proc = rt.exec(command);

            BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(proc.getInputStream()));

            BufferedReader stdError = new BufferedReader(new
                    InputStreamReader(proc.getErrorStream()));

            output += "\nCommand output:\n";
            String s = null;
            while ((s = stdInput.readLine()) != null) {
                output += s + "\n";
            }

            output += "\nSTD Error output (if any):\n";
            while ((s = stdError.readLine()) != null) {
                output += s + "\n";
            }

            output +="\nCommand exit code: " + proc.exitValue() +" \n";
            return output;
        }

        private String buildCommand(String workspace, String outputFileName) {
            StringBuilder sb = new StringBuilder();
            sb.append(this.normalizeExecutable(this.mobileStudioRunnerPath));

            sb.append(" ");
            sb.append("/msgServer=\"");
            sb.append(this.msgServer);
            sb.append("\"");

            sb.append(" ");
            sb.append("/project=\"");
            sb.append(normalizePath(workspace, this.projectRoot));
            sb.append("\"");

            sb.append(" /");
            sb.append(this.testType);
            sb.append("=\"");
            sb.append(this.testPath);
            sb.append("\"");

            if (!Utils.isEmpty(this.deviceId)) {
                sb.append(" ");
                sb.append("/deviceId=\"");
                sb.append(this.deviceId);
                sb.append("\"");
            }

            sb.append(" ");
            sb.append("/output=\"");
            sb.append(normalizePath(workspace, Constants.MOBILE_STUDIO_RESULTS_DIR + File.separator + outputFileName));
            sb.append("\"");

            sb.append(" ");
            sb.append("/resultType=");
            if (this.testAsUnit) {
                sb.append("1");
            } else {
                sb.append("2");
            }

            return sb.toString();
        }

        private String normalizeExecutable(String mobileStudioRunnerPath) {
            String pathToLowerCase = mobileStudioRunnerPath.toLowerCase();
            String command = "";
            if (!isWindows){
                command = "mono \"";
            } else {
                command = "\"";
            }
            if (pathToLowerCase.endsWith(File.separator)) {
                return command + mobileStudioRunnerPath + TELERIK_MOBILE_TESTING_RUNNER + "\"";
            } else if (!pathToLowerCase.endsWith(TELERIK_MOBILE_TESTING_RUNNER.toLowerCase())) {
                return command + mobileStudioRunnerPath + "\\" + TELERIK_MOBILE_TESTING_RUNNER + "\"";
            }
            return command + mobileStudioRunnerPath + "\"";
        }

        private String normalizePath(String workspace, String path){
            String result;
            if (isWindows) {
                Matcher m = Pattern.compile("^\\D:\\\\.*$").matcher(path);
                if (!m.find()) {
                    if (path.startsWith(File.separator)) {
                        result = workspace + path;
                    } else {
                        result = workspace + File.separator + path;
                    }
                } else {
                    result = path;
                }
                if (result.endsWith(File.separator)) {
                    result = result.substring(0, result.length() - 1);
                }
            } else {
                if (path.startsWith(File.separator)) {
                    result = path;
                } else {
                    result = workspace + File.separator + path;
                }
            }
            return result;
        }
    }

    @SuppressWarnings("unused")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @SuppressWarnings("unused")
        public FormValidation doCheckMobileStudioRunnerPath(@QueryParameter String mobileStudioRunnerPath) throws IOException, ServletException {
            if (Utils.isEmpty(mobileStudioRunnerPath)) {
                return FormValidation.error(Messages.MobileStudioTestBuilder_DescriptorImpl_errors_zero_mobileStudioTestRunnerPath());
            } else {
                File f = new File(mobileStudioRunnerPath);
                if (!f.exists()) {
                    return FormValidation.error(Messages.MobileStudioTestBuilder_DescriptorImpl_errors_notFound_mobileStudioTestRunnerPath());
                }
            }
            return FormValidation.ok();
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckTestPath(@QueryParameter String testPath) throws IOException, ServletException {

            if (Utils.isEmpty(testPath)) {
                return FormValidation.error(Messages.MobileStudioTestBuilder_DescriptorImpl_errors_zero_testPath());
            } else {
                File f = new File(testPath);
                if (!f.exists()) {
                    return FormValidation.error(Messages.MobileStudioTestBuilder_DescriptorImpl_errors_notFound_testPath());
                } else if (!testPath.endsWith(TEST) && !testPath.endsWith(LIST)) {
                    return FormValidation.error(Messages.MobileStudioTestBuilder_DescriptorImpl_errors_extension_testPath());
                }
            }

            return FormValidation.ok();
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckMsgServer(@QueryParameter String msgServer) throws IOException, ServletException {

            if (Utils.isEmpty(msgServer)) {
                return FormValidation.error(Messages.MobileStudioTestBuilder_DescriptorImpl_errors_zero_msgServer());
            }

            return FormValidation.ok();
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckDeviceId(@QueryParameter String deviceId) throws IOException, ServletException {

            if (Utils.isEmpty(deviceId)) {
                return FormValidation.error(Messages.MobileStudioTestBuilder_DescriptorImpl_errors_zero_deviceId());
            }

            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.MobileStudioTestBuilder_DescriptorImpl_DisplayName();
        }

    }

    @SuppressWarnings("unused")
    public String getTestPath() {
        return testPath;
    }

    @SuppressWarnings("unused")
    public void setTestPath(String testPath) {
        this.testPath = testPath;
    }

    @SuppressWarnings("unused")
    public String getMobileStudioRunnerPath() {
        return mobileStudioRunnerPath;
    }

    public boolean isTestAsUnit() {
        return this.testAsUnit;
    }

    @DataBoundSetter
    public void setTestAsUnit(boolean testAsUnit) {
        this.testAsUnit = testAsUnit;
    }

    public String getProjectRoot() {
        return projectRoot;
    }

    @DataBoundSetter
    public void setProjectRoot(String projectRoot) {
        this.projectRoot = projectRoot;
    }

    public String getMsgServer() {
        return msgServer;
    }

    @DataBoundSetter
    public void setMsgServer(String msgServer) {
        this.msgServer = msgServer;
    }

    public String getDeviceId() {
        return deviceId;
    }

    @DataBoundSetter
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }
}



