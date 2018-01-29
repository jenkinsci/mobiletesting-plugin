package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jenkinsci.plugins.Constants.LIST;
import static org.jenkinsci.plugins.Constants.TELERIK_MOBILE_TESTING_RUNNER;
import static org.jenkinsci.plugins.Constants.TEST;
import static org.jenkinsci.plugins.Utils.fileExists;
import static org.jenkinsci.plugins.Utils.isEmpty;

@SuppressWarnings("unused")
public class TestBuilder extends Builder implements SimpleBuildStep {

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
    public TestBuilder(String mobileStudioRunnerPath,
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
        if (System.getProperty("os.name").toLowerCase().contains("windows")){
            this.isWindows = true;
        }

        String workspacePath = run.getEnvironment(listener).get("WORKSPACE", null);
        String outputFileName = "MobileStudioResults-" + System.currentTimeMillis() + ".xml";
        String command = buildCommand(workspacePath, outputFileName);

        prepareWorkspace(workspacePath);
        Runtime rt = Runtime.getRuntime();
        Process proc = rt.exec(command);

        BufferedReader stdInput = new BufferedReader(new
                InputStreamReader(proc.getInputStream()));

        BufferedReader stdError = new BufferedReader(new
                InputStreamReader(proc.getErrorStream()));

        listener.getLogger().println("Command output:\n");
        String s = null;
        while ((s = stdInput.readLine()) != null) {
            listener.getLogger().println(s);
        }

        listener.getLogger().println("STD Error output (if any):\n");
        while ((s = stdError.readLine()) != null) {
            listener.error(s);
        }

        String fullOutputName = workspace + File.separator + Constants.MOBILE_STUDIO_RESULTS_DIR + File.separator + outputFileName ;
        if (!fileExists(fullOutputName)) {
            listener.error("Result file doesn't exists: " + fullOutputName);
            run.setResult(Result.FAILURE);
        }
    }

    private void prepareWorkspace(String workspace) {
        File index = new File(workspace + File.separator + Constants.MOBILE_STUDIO_RESULTS_DIR);
        if (!index.exists()) {
            index.mkdir();
        } else {
            String[] entries = index.list();
            for (String s : entries) {
                File currentFile = new File(index.getPath(), s);
                currentFile.delete();
            }
            if (!index.exists()) {
                index.mkdir();
            }
        }
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
        sb.append(this.testType.toString());
        sb.append("=\"");
        sb.append(this.testPath);
        sb.append("\"");

        if (!isEmpty(this.deviceId)) {
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
            command = "mono ";
        }
        if (pathToLowerCase.endsWith(File.separator)) {
            return command + mobileStudioRunnerPath + TELERIK_MOBILE_TESTING_RUNNER;
        } else if (!pathToLowerCase.endsWith(TELERIK_MOBILE_TESTING_RUNNER.toLowerCase())) {
            return command + mobileStudioRunnerPath + "\\" + TELERIK_MOBILE_TESTING_RUNNER;
        }
        return command + mobileStudioRunnerPath;
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

    @SuppressWarnings("unused")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @SuppressWarnings("unused")
        public FormValidation doCheckMobileStudioRunnerPath(@QueryParameter String mobileStudioRunnerPath) throws IOException, ServletException {
            if (isEmpty(mobileStudioRunnerPath)) {
                return FormValidation.error(Messages.TestBuilder_DescriptorImpl_errors_zero_mobileStudioTestRunnerPath());
            } else {
                File f = new File(mobileStudioRunnerPath);
                if (!f.exists()) {
                    return FormValidation.error(Messages.TestBuilder_DescriptorImpl_errors_notFound_mobileStudioTestRunnerPath());
                }
            }
            return FormValidation.ok();
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckTestPath(@QueryParameter String testPath) throws IOException, ServletException {

            if (isEmpty(testPath)) {
                return FormValidation.error(Messages.TestBuilder_DescriptorImpl_errors_zero_testPath());
            } else {
                File f = new File(testPath);
                if (!f.exists()) {
                    return FormValidation.error(Messages.TestBuilder_DescriptorImpl_errors_notFound_testPath());
                } else if (!testPath.endsWith(TEST) && !testPath.endsWith(LIST)) {
                    return FormValidation.error(Messages.TestBuilder_DescriptorImpl_errors_extension_testPath());
                }
            }

            return FormValidation.ok();
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckMsgServer(@QueryParameter String msgServer) throws IOException, ServletException {

            if (isEmpty(msgServer)) {
                return FormValidation.error(Messages.TestBuilder_DescriptorImpl_errors_zero_msgServer());
            }

            return FormValidation.ok();
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckDeviceId(@QueryParameter String deviceId) throws IOException, ServletException {

            if (isEmpty(deviceId)) {
                return FormValidation.error(Messages.TestBuilder_DescriptorImpl_errors_zero_deviceId());
            }

            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.TestBuilder_DescriptorImpl_DisplayName();
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



