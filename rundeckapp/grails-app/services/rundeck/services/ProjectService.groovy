/*
 * Copyright 2016 SimplifyOps, Inc. (http://simplifyops.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rundeck.services
import com.dtolabs.rundeck.app.support.BuilderUtil
import com.dtolabs.rundeck.app.support.ProjectArchiveExportRequest
import com.dtolabs.rundeck.app.support.ProjectArchiveImportRequest
import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.core.authorization.AuthContextEvaluator
import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import com.dtolabs.rundeck.core.authorization.Validation
import com.dtolabs.rundeck.core.common.Framework
import com.dtolabs.rundeck.core.common.IFilesystemFramework
import com.dtolabs.rundeck.core.common.IFramework
import com.dtolabs.rundeck.core.execution.ExecutionReference
import com.dtolabs.rundeck.core.plugins.configuration.Property
import com.dtolabs.rundeck.core.plugins.configuration.Validator
import com.dtolabs.rundeck.net.model.ProjectImportStatus
import com.dtolabs.rundeck.net.api.Client
import com.dtolabs.rundeck.util.XmlParserUtil
import com.dtolabs.rundeck.util.ZipBuilder
import com.dtolabs.rundeck.util.ZipReader
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.RemovalNotification
import grails.async.Promises
import grails.events.EventPublisher
import grails.gorm.transactions.Transactional
import groovy.transform.CompileStatic
import groovy.transform.ToString
import groovy.xml.MarkupBuilder
import org.apache.commons.io.FileUtils
import org.rundeck.app.acl.AppACLContext
import org.rundeck.app.acl.ContextACLManager
import org.rundeck.app.components.project.BuiltinExportComponents
import org.rundeck.app.components.project.BuiltinImportComponents
import org.rundeck.app.components.project.ProjectComponent
import org.rundeck.core.auth.AuthConstants
import org.rundeck.util.Toposort
import org.rundeck.app.components.RundeckJobDefinitionManager
import org.rundeck.app.components.jobs.JobDefinitionException
import org.rundeck.app.components.jobs.JobFormat
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import com.dtolabs.rundeck.core.common.IRundeckProject
import org.springframework.context.ApplicationContext
import org.springframework.transaction.TransactionStatus
import rundeck.BaseReport
import rundeck.ExecReport
import rundeck.Execution
import rundeck.JobFileRecord
import rundeck.ScheduledExecution
import rundeck.codecs.JobsXMLCodec
import org.rundeck.app.services.ExecutionFile

import org.rundeck.app.services.ExecutionFileProducer
import rundeck.services.logging.ProducedExecutionFile

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.function.Function
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Transactional
class ProjectService implements InitializingBean, ExecutionFileProducer, EventPublisher {
    public static final String EXECUTION_XML_LOG_FILETYPE = 'execution.xml'
    public static final String PROJECT_BASEDIR_PROPS_PLACEHOLDER = '%PROJECT_BASEDIR%'
    final String executionFileType = EXECUTION_XML_LOG_FILETYPE

    def grailsApplication
    def scheduledExecutionService
    def executionService
    def fileUploadService
    def loggingService
    def logFileStorageService
    def workflowService
    ContextACLManager<AppACLContext> aclFileManagerService
    def scmService
    def executionUtilService
    def AuthContextEvaluator rundeckAuthContextEvaluator

    RundeckJobDefinitionManager rundeckJobDefinitionManager
    static transactional = false

    static Logger projectLogger = LoggerFactory.getLogger("org.rundeck.project.events")

    private exportJob(ScheduledExecution job, Writer writer, String stripJobRef = null)
        throws ProjectServiceException {
        //convert map to xml
        rundeckJobDefinitionManager.exportAs('xml', [job], JobFormat.options(true, [:], stripJobRef), writer)
    }

    def exportHistoryReport(ZipBuilder zip, BaseReport report, String name) throws ProjectServiceException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        def dateConvert = {
            sdf.format(it)
        }
        BuilderUtil builder = new BuilderUtil(converters:[(Date): dateConvert, (java.sql.Timestamp): dateConvert])
        def map = report.toMap()
        if(map.jcJobId){
            //convert internal job ID to extid
            def se
            try{
                se = ScheduledExecution.get(Long.parseLong(map.jcJobId))
                if(se){
                    map.jcJobId=se.extid
                }
            }catch(NumberFormatException e){

            }
        }
        //convert map to xml
        zip.file("$name"){ Writer writer ->
            def xml = new MarkupBuilder(writer)
            builder.objToDom("report", map, xml)
        }
    }

    def exportFileRecord(ZipBuilder zip, JobFileRecord record, String name) throws ProjectServiceException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        def dateConvert = {
            sdf.format(it)
        }
        BuilderUtil builder = new BuilderUtil(converters: [(Date): dateConvert, (java.sql.Timestamp): dateConvert])
        def map = record.toMap()

        //convert map to xml
        zip.file("$name") { Writer writer ->
            def xml = new MarkupBuilder(writer)
            builder.objToDom("jobFileRecord", map, xml)
        }
    }

    @Override
    boolean isExecutionFileGenerated() {
        return true
    }

    @Override
    boolean isCheckpointable() {
        return false
    }

    @Override
    ExecutionFile produceStorageCheckpointForExecution(final ExecutionReference e) {
        return null
    }

    @Override
    ExecutionFile produceStorageFileForExecution(final ExecutionReference e) {
        File localfile = logFileStorageService.
                getFileForExecutionFiletype(Execution.get(e.id), EXECUTION_XML_LOG_FILETYPE, false)

        new ProducedExecutionFile(localFile: localfile, fileDeletePolicy: ExecutionFile.DeletePolicy.ALWAYS)
    }

    def exportExecution(ZipBuilder zip, Execution exec, String name) throws ProjectServiceException {

        def File logfile = loggingService.getLogFileForExecution(exec)
        String logfilepath=null
        if (logfile && logfile.isFile()) {
            logfilepath = "output-${exec.id}.rdlog"
        }
        //convert map to xml
        zip.file("$name") { Writer writer ->
            executionUtilService.exportExecutionXml(exec, writer, logfilepath)
        }
        if (logfile && logfile.isFile()) {
            zip.file logfilepath, logfile
        }
        def File statefile = workflowService.getStateFileForExecution(exec)
        if (statefile && statefile.isFile()) {
            zip.file "state-${exec.id}.state.json", statefile
        }
    }

    /**
     * Parse XML and return a ExecReport/BaseReport object
     * @param xmlinput xml source
     * @param execIdMap map of old execution IDs to new Ids
     * @param jobsByOldIdMap map of old Job IDs to new Job entries
     * @return Report object with remapped exec/job ID values
     * @throws ProjectServiceException
     */
    def loadHistoryReport(xmlinput, Map execIdMap=null, Map jobsByOldIdMap =null, identity=null) throws ProjectServiceException {
        Node doc = parseXml(xmlinput)
        if (!doc) {
            throw new ProjectServiceException("XML Document could not be parsed.")
        }
        if (doc.name() != 'report') {
            throw new ProjectServiceException("Document root tag was not 'executions': '${doc.name()}'")
        }

        //load doc as report
        def object = XmlParserUtil.toObject(doc)
        if (object instanceof Map) {
            //remap job id if necessary
            if (object.jcJobId && jobsByOldIdMap && jobsByOldIdMap[object.jcJobId]) {
                object.jcJobId= jobsByOldIdMap[object.jcJobId].id
            }
            //remap exec id if necessary
            if (object.jcExecId && execIdMap && execIdMap[object.jcExecId]) {
                object.jcExecId= execIdMap[object.jcExecId]
            }else {
                //skip report for exec id that cannot be found
                return null
            }
            //convert dates
            convertStringsToDates(object, ['dateStarted', 'dateCompleted'],"Report ${identity}")
            if (!(object.dateCompleted instanceof Date)) {
                object.dateCompleted = new Date()
            }
            def report
            try {
                report = ExecReport.fromMap(object)
            } catch (Throwable e) {
                throw new ProjectServiceException("Unable to create Report: " + e.getMessage(), e)
            }
            return report
        } else {
            throw new ProjectServiceException("Unexpected data type for Report: " + object.class.name)
        }
    }
    /**
     * Parse XML and return a JobFileRecord object
     * @param xmlinput xml source
     * @param execIdMap map of old execution IDs to new Ids
     * @param jobIdMap map of old Job IDs to new Job Ids
     * @return Report object with remapped exec/job ID values
     * @throws ProjectServiceException
     */
    JobFileRecord loadJobFileRecord(xmlinput, Map execIdMap = null, Map jobIdMap = null, identity = null)
            throws ProjectServiceException
    {
        Node doc = parseXml(xmlinput)
        if (!doc) {
            throw new ProjectServiceException("XML Document could not be parsed.")
        }
        if (doc.name() != 'jobFileRecord') {
            throw new ProjectServiceException("Document root tag was not 'jobFileRecord': '${doc.name()}'")
        }

        //load doc as report
        def object = XmlParserUtil.toObject(doc)
        if (object instanceof Map) {
            //remap job id if necessary
            if (object.jobId && jobIdMap && jobIdMap[object.jobId]) {
                object.jobId = jobIdMap[object.jobId]
            }
            //remap exec id if necessary

            if (!(object.execId && execIdMap && execIdMap[object.execId])) {
                //skip report for exec id that cannot be found
                return null
            }
            def newid = execIdMap[object.execId]
            def eid = (newid instanceof Long) ? newid : Long.parseLong(newid)
            Execution exec = Execution.get(eid)
            object.execution = exec
            //convert dates
            convertStringsToDates(object, ['dateCreated', 'lastUpdated', 'expirationDate'], "JobFileRecord ${identity}")
            //remap uuid
            def newuuid = UUID.randomUUID().toString()
            def olduuid = object.uuid
            object.uuid = newuuid
            def report
            try {
                report = JobFileRecord.fromMap(object)
            } catch (Throwable e) {
                throw new ProjectServiceException("Unable to create JobFileRecord: " + e.getMessage(), e)
            }
            def oldargstring = exec.argString
            exec.argString = oldargstring.replaceAll(
                    Pattern.quote(olduuid),
                    Matcher.quoteReplacement(newuuid)
            )
            log.error("Replace execution argstring $oldargstring, new $exec.argString for $exec.id")
            //modify argstring of execution
            return report
        } else {
            throw new ProjectServiceException("Unexpected data type for JobFileRecord: " + object.class.name)
        }
    }

    private void convertStringsToDates(object, final ArrayList<String> properties, identity=null) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        properties.each {dname ->
            def dobj = object[dname]
            if (dobj && dobj instanceof String) {
                try {
                    object[dname] = sdf.parse(dobj)
                } catch (ParseException e) {
                    throw new ProjectServiceException("Unable to parse date in W3C format ('${dname}' for ${identity})", e)
                }
            } else if (dobj && !(dobj instanceof Date)) {
                throw new ProjectServiceException("Expected a date format: ('${dname}' for ${identity})")
            }
        }
    }
    /**
     * Parse Execution objects from an XML file, and return the set of parsed executions
     * @param xmlfile input file
     * @param jobIdMap map of UUID/ScheduledExecution IDs to new UUIDs for reassigning execution to jobs
     * @param skipJobIds list of UUID/job id to skip execution import
     * @return map data: 'executions' list of Executions that were parsed, 'execidmap' map of new Executions to the
     * input IDs from the XML, 'retryidmap' map of new Executions to old the 'retry' execution ID
     * @throws ProjectServiceException if an error occurs
     */
    def loadExecutions(xmlinput, String projectName, Map jobIdMap=null, skipJobIds = []) throws ProjectServiceException {
        Node doc = parseXml(xmlinput)
        if (!doc) {
            throw new ProjectServiceException("XML Document could not be parsed.")
        }
        if (doc.name() != 'executions') {
            throw new ProjectServiceException("Document root tag was not 'executions': '${doc.name()}'")
        }
        if (!doc.execution || doc.execution.size() < 1) {
            throw new ProjectServiceException("No 'executions/execution' element was found")
        }

        def execlist=[]
        def execidmap=[:]
        def retryidmap=[:]
        def ecount=0
        doc.execution.each{ enode->
            def object = XmlParserUtil.toObject(enode,false)
            if (object instanceof Map) {
                JobsXMLCodec.convertXmlWorkflowToMap(object.workflow)
                //remap job id if necessary
                def se=null
                if(object.jobId && jobIdMap && jobIdMap[object.jobId]){
                    se=scheduledExecutionService.getByIDorUUID(jobIdMap[object.jobId])
                }else if(object.jobId && skipJobIds && skipJobIds.contains(object.jobId)){
                    log.debug("Execution skipped ${object.id} for job ${object.jobId}")
                    return
                }else if(object.jobId) {
                    //look for same ID
                    def found = scheduledExecutionService.getByIDorUUID(object.jobId)
                    if(found && found.project==projectName){
                        se=found
                    }

                }
                if(object.id){
                    object.id=XmlParserUtil.stringToInt(object.id,-1)
                }
                //convert dates
                convertStringsToDates(object, ['dateStarted', 'dateCompleted'], "Execution($ecount) ID ${object.id}")
                if(!(object.dateCompleted instanceof Date)){
                    object.dateCompleted=new Date()
                    object.status='false'
                    object.cancelled=true
                    object.abortedby='system'
                }
                def retryExecId= XmlParserUtil.stringToInt(object.remove('retryExecutionId'),0)
                try {
                    def newexec = Execution.fromMap(object, se)
                    execidmap[newexec]=object.id
                    if(retryExecId){
                        retryidmap[newexec]=retryExecId
                    }
                    execlist << newexec
                } catch (Throwable e) {
                    throw new ProjectServiceException("Unable to create Execution($ecount): " + e.getMessage(), e)
                }
                ecount++
            } else {
                throw new ProjectServiceException("Unexpected data type for Execution($ecount): " + object.class.name)
            }
        }
        [executions:execlist,execidmap:execidmap, retryidmap: retryidmap]
    }

    private Node parseXml(xmlinput) {
        def XmlParser parser = new XmlParser()
        def doc
        def reader
        def filestream
        if (xmlinput instanceof File ) {
            filestream=new FileInputStream(xmlinput)
            reader = new InputStreamReader(filestream,"UTF-8")
        } else if (xmlinput instanceof InputStream) {
            reader = new InputStreamReader(xmlinput,"UTF-8")
        } else if (xmlinput instanceof String) {
            reader = new StringReader(xmlinput)
        } else {
            throw new IllegalArgumentException("Unexpected input: ${xmlinput}")
        }

        try {
            doc = parser.parse(reader)
        } catch (Exception e) {
            throw new ProjectServiceException("Unable to parse xml: ${e.message}",e)
        }finally{
            if(null!=filestream){
                filestream.close()
            }
        }
        doc
    }

    /**
     * Cache of async project export promises
     */
    def Cache<String, ArchiveRequest> asyncExportResults
    /**
     * retains the requests until they can be left to expire in the cache
     */
    def Map<String, ArchiveRequest> asyncExportRequests

    BeanProvider<ProjectComponent> componentBeanProvider = new SpringBeanProvider<ProjectComponent>(
        applicationContext: applicationContext,
        clazz: ProjectComponent
    )

    @Override
    void afterPropertiesSet() throws Exception {

        asyncExportRequests= Collections.synchronizedMap(new HashMap<String,ArchiveRequest>())

        def spec=grailsApplication.config.rundeck?.projectService?.projectExportCache?.spec?: "expireAfterAccess=30m"

        asyncExportResults=
                CacheBuilder.
                        from(spec).
                        removalListener({ RemovalNotification<String,ArchiveRequest> notification->
                        //when cached item is removed, delete the file if the requests map is not retaining it
                            if(!asyncExportRequests.containsKey(notification.key)){
                                log.debug("Cache expired for project archive request ${notification.key}, deleting file: ${notification.value.file}")
                                notification.value.file?.delete()
                            }
                        } ).
                        build({
                         //load the request via the requests map, otherwise it does not exist
                            def request = asyncExportRequests.get(it);
                            if (null == request) {
                                throw new Exception("Invalid key: ${it}")
                            }
                            request
                        } )
        componentBeanProvider = new SpringBeanProvider<ProjectComponent>(
            applicationContext: applicationContext,
            clazz: ProjectComponent
        )
    }

    /**
     * Begin asynchronous export request, return a token String to identify
     * @param project project
     * @param framework framework
     * @param ident username or identify of requestor
     * @return token string to identify the new request
     */
    def exportProjectToFileAsync(
        IRundeckProject project,
        IFramework framework,
        String ident,
        ProjectArchiveExportRequest options,
        AuthContext authContext
    )
    {
        String token = UUID.randomUUID().toString()
        def summary=new ArchiveRequestProgress()
        def request=new ArchiveRequest(summary:summary,token:token)

        def p = Promises.task {
            try {
                ScheduledExecution.withNewSession {
                    request.file = exportProjectToFile(project, framework, summary, options, authContext)
                    log.debug("Async archive request with token ${token} finished successfully")
                }
            } catch (Throwable t) {
                log.error("Async archive request with token ${token} failed: ${t}", t)
                request.exception = t
            }
        }
        //store in map
        asyncExportRequests.put(ident+'/'+token,request)
        token
    }

    def exportProjectToInstanceAsync(
            IRundeckProject project,
            Framework framework,
            String ident,
            ProjectArchiveExportRequest options,
            String iProject,
            String apiToken,
            String instanceUrl,
            boolean preserveUUID,
            AuthContext authContext
    )
    {
        projectLogger.info("Begin export ["+ project.name + "] to ["+instanceUrl+"]/"+project)
        String token = UUID.randomUUID().toString()
        def summary=new ExportFileProgress()
        def request=new ExportFileRequest(summary:summary,token:token)

        def p = Promises.task {
            try {
                ScheduledExecution.withNewSession {
                    request.project = iProject
                    request.apitoken = apiToken
                    request.instance = instanceUrl
                    request.result = exportProjectToInstance(project, framework, summary, options,
                                    iProject, apiToken,instanceUrl,preserveUUID, authContext)
                    request.file = request.result.file
                    projectLogger.info("Export ["+ project.name + "] to ["+instanceUrl+"]/"+project + " succeeded")
                }
            } catch (Throwable t) {
                projectLogger.info("Export of ${project.name} failed: ${t}", t)
                request.exception = t
            }
        }
        //store in map
        asyncExportRequests.put(ident+'/'+token,request)
        token
    }
    /**
     * Attempt to get the request from the cache, or return null if it is not available
     * @param key key
     * @return request
     */
    private def ArchiveRequest getRequest(String key){
        try{
            return asyncExportResults.get(key)
        }catch (Exception e){
            return null
        }
    }
    /**
     * Return true if the request is still available
     * @param ident
     * @param token
     * @return
     */
    def boolean hasPromise(String ident,String token){
        return getRequest(ident+'/'+token)!=null
    }
    /**
     * Return summary of progress for the request, or null
     * @param ident
     * @param token
     * @return
     */
    def ProgressSummary promiseSummary(String ident,String token){
        getRequest(ident+'/'+token)?.summary
    }
    /**
     *
     * @param ident
     * @param token
     * @return result file, or null
     */
    def File promiseReady(String ident,String token){
        getRequest(ident+'/'+token)?.file
    }
    /**
     *
     * @param ident
     * @param token
     * @return result request
     */
    def ImportResponse promiseResult(String ident,String token){
        getRequest(ident+'/'+token)?.result
    }
    /**
     *
     * @param ident
     * @param token
     * @return date of request, or null
     */
    def Date promiseRequestStarted(String ident,String token){
        getRequest(ident+'/'+token)?.dateStarted
    }
    /**
     *
     * @param ident
     * @param token
     * @return exception thrown, or null
     */
    def Throwable promiseError(String ident,String token){
        return getRequest(ident+'/'+token)?.exception
    }
    /**
     * marks the request to be expired
     * @param ident
     * @param token
     * @return
     */
    def void releasePromise(String ident,String token){
        //remove from map, allow to expire via the cache
        asyncExportRequests.remove(ident+'/'+token)
    }
    /**
     * Export the project to a temp file jar
     * @param project
     * @param framework
     * @param listener a progress listener
     * @param aclReadAuth true if ACL read access is granted, will include ACLs
     * @return
     * @throws ProjectServiceException
     */
    def exportProjectToFile(
        IRundeckProject project, IFramework framework, ProgressListener listener,
        ProjectArchiveExportRequest options, AuthContext authContext
    ) throws ProjectServiceException {
        def outfile
        try {
            outfile = File.createTempFile("export-${project.name}", ".jar")
        } catch (IOException exc) {
            throw new ProjectServiceException("Could not create temp file for archive: " + exc.message, exc)
        }
        outfile.withOutputStream { output ->
            exportProjectToOutputStream(project, framework, output, listener, options, authContext)
        }
        outfile.deleteOnExit()
        outfile
    }
    def exportProjectToInstance(IRundeckProject project, Framework framework, ProgressListener listener,
                                ProjectArchiveExportRequest options,String iProject,
                                String apiToken, String instanceUrl,boolean preserveUUID,
                                AuthContext authContext
    ) throws ProjectServiceException{
        File file = exportProjectToFile(project, framework, listener, options, authContext)
        Client client = new Client(instanceUrl,apiToken)
        ProjectImportStatus ret = client.importProjectArchive(iProject,file,true, options.executions,
                    options.configs,options.acls, options.scm)
        ImportResponse response = new ImportResponse(file:file,errors: ret.errors, ok:ret.getResultSuccess(),
                executionErrors:ret.executionErrors, aclErrors:ret.aclErrors)
        listener?.done()
        response
    }
    /**
     * Export the project to an outputstream
     * @param project
     * @param framework
     * @return
     * @throws ProjectServiceException
     */
    def exportProjectToOutputStream(IRundeckProject project,
                                    IFramework framework,
                                    OutputStream stream,
                                    ProgressListener listener,
                                    ProjectArchiveExportRequest options,
                                    AuthContext authContext
    ) throws ProjectServiceException
    {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));

        def Manifest manifest = new Manifest()
        manifest.mainAttributes.put(Attributes.Name.MANIFEST_VERSION,'1.0')
        manifest.mainAttributes.putValue('Rundeck-Application-Version', grailsApplication.metadata.getApplicationVersion())
        manifest.mainAttributes.putValue('Rundeck-Archive-Format-Version', '1.0')
        manifest.mainAttributes.putValue('Rundeck-Archive-Project-Name', project.name)
        manifest.mainAttributes.putValue('Rundeck-Archive-Export-Date', sdf.format(new Date()))

        def zip = new JarOutputStream(stream,manifest)
        try {
            exportProjectToStream(project, framework, zip, listener, options, authContext)
        } finally {
            zip.close()
        }
    }

    def exportProjectToStream(
            IRundeckProject project,
            IFramework framework,
            ZipOutputStream output,
            ProgressListener listener,
            ProjectArchiveExportRequest options,
            AuthContext authContext
    ) throws ProjectServiceException
    {
        ZipBuilder zip = new ZipBuilder(output)
//        zip.debug = true
        String projectName = project.name
        def isExportJobs = !options || options.all || options.jobs
        def isExportExecutions = !options || options.all || options.executions
        def isExportConfigs = !options || options.all || options.configs
        def isExportReadmes = !options || options.all || options.readmes
        def isExportAcls = hasAclReadAuth(authContext,project.name) && (!options || options.all || options.acls)
        def isExportScm = hasScmConfigure(authContext,project.name) && (!options || options.all || options.scm)
        def stripJobRef = (options.stripJobRef != 'no')?options.stripJobRef:null
        Map<String, ProjectComponent> authedComponents = getProjectComponentsAuthorizedForExport(
            authContext,
            project.name
        )
        Map<String,ProjectComponent> enabledComponents=authedComponents.findAll { name, exporter ->
            options.all ||
            !exporter.exportOptional ||
            (options.exportComponents && options.exportComponents[name])
        }
        def baseRunBefore = [(BuiltinExportComponents.jobs.name()): [BuiltinExportComponents.executions.name()]]
        def sortOrder= (BuiltinExportComponents.names()) + (enabledComponents?.keySet()?.toSorted() ?: [])

        def sortResult=Toposort.toposort(sortOrder, { String name->
            if(baseRunBefore[name]){
                return baseRunBefore[name]
            }
            return enabledComponents[name]?.exportMustRunBefore
        }, {String name->
            enabledComponents[name]?.exportMustRunAfter
                                         })
        if(sortResult.result){
            sortOrder=sortResult.result
        }else{
            //cyclic dependencies
            log.warn("Project Import: component ordering could not be determined due to cyclic dependency: ${sortResult.cycle}")
        }

        if (options && options.executionsOnly) {
            listener?.total(
                    'export',
                    4 * options.executionIds.size()
            )
        } else {
            def total = 0
            if (isExportExecutions) {
                total += 3 * Execution.countByProject(projectName) + BaseReport.countByCtxProject(projectName)
            }
            if (isExportJobs) {
                total += ScheduledExecution.countByProject(projectName)
            }
            if (isExportConfigs) {
                total += 1
            }
            if (isExportReadmes) {
                total += 1
            }
            if (isExportAcls) {
                total += 1
            }
            if (isExportScm){
                total += 1
            }
            total += enabledComponents.size()
            listener?.total('export', total)
        }

        zip.dir("rundeck-${projectName}/") {
            //export jobs
            sortOrder.each{compName->

                if (compName == BuiltinExportComponents.jobs.name() && isExportJobs) {
                    def jobs = ScheduledExecution.findAllByProject(projectName)
                    dir('jobs/') {
                        jobs.each { ScheduledExecution job ->
                            zip.file("job-${job.extid.encodeAsURL()}.xml") { Writer writer ->
                                exportJob job, writer, stripJobRef
                                listener?.inc('export', 1)
                            }
                        }
                    }
                } else if (compName == BuiltinExportComponents.executions.name()) {
                    List<Execution> execs=[]
                    List<BaseReport> reports=[]
                    if (options.executionsOnly) {
                        //find execs
                        List<Long> execIds = []
                        List<String> execIdStrings = []
                        options.executionIds.each {
                            if (it instanceof Long) {
                                execIds << it
                                execIdStrings << it.toString()
                            } else if (it instanceof String) {
                                execIds << Long.parseLong(it)
                                execIdStrings << it
                            }
                        }
                        execs = Execution.findAllByProjectAndIdInList(projectName, execIds)
                        reports = ExecReport.findAllByCtxProjectAndJcExecIdInList(projectName, execIdStrings)
                    } else if (isExportExecutions) {
                        execs = Execution.findAllByProject(projectName)
                        reports = BaseReport.findAllByCtxProject(projectName)
                    }
                    List<JobFileRecord> jobfilerecords = []

                    if (execs) {
                        dir('executions/') {
                            //export executions
                            //export execution logs
                            execs.each { Execution exec ->
                                exportExecution zip, exec, "execution-${exec.id}.xml"

                                jobfilerecords.addAll JobFileRecord.findAllByExecution(exec)

                                listener?.inc('export', 3)
                            }
                        }

                        dir('jobfiles/') {
                            jobfilerecords.each { JobFileRecord record ->
                                exportFileRecord zip, record, "filerecord-${record.id}.xml"
                            }
                        }
                        //export history

                        dir('reports/') {
                            reports.each { BaseReport report ->
                                exportHistoryReport zip, report, "report-${report.id}.xml"
                                listener?.inc('export', 1)
                            }
                        }
                    }

                } else if (compName == BuiltinExportComponents.config.name()) {
                    //export config
                    if (isExportConfigs || isExportReadmes || isExportAcls || isExportScm) {
                        dir('files/') {
                            if (isExportConfigs) {
                                dir('etc/') {
                                    zip.file('project.properties') { Writer writer ->
                                        def map = replaceBasedirForProperties(
                                            project,
                                            framework,
                                            project.getProjectProperties()
                                        )
                                        def projectProps = map as Properties
                                        def sw = new StringWriter()
                                        projectProps.store(sw, "Exported configuration")
                                        def projectPropertiesText = sw.toString().
                                                split(Pattern.quote(System.getProperty("line.separator"))).
                                                sort().
                                                join(System.getProperty("line.separator"))
                                        writer.write(projectPropertiesText)

                                        listener?.inc('export', 1)
                                    }
                                }
                            }
                            if (isExportReadmes) {
                                ['readme.md', 'motd.md'].each { filename ->
                                    if (project.existsFileResource(filename)) {
                                        zip.fileStream(filename) { OutputStream stream ->
                                            project.loadFileResource(filename, stream)
                                        }
                                    }
                                }
                                listener?.inc('export', 1)
                            }
                            if (isExportAcls) {
                                //acls
                                def manager = aclFileManagerService.forContext(AppACLContext.project(project.name))
                                def policies = manager.listStoredPolicyFiles()
                                if (policies) {
                                    dir('acls/') {
                                        policies.each { fname ->
                                            zip.fileStream(fname) { OutputStream stream ->
                                                manager.loadPolicyFileContents(fname,stream)
                                            }
                                        }
                                    }
                                }
                                listener?.inc('export', 1)
                            }

                            if (isExportScm) {
                                ['import', 'export'].each { integration ->
                                    def scmconfig = scmService.loadScmConfig(projectName, integration)
                                    if (scmconfig) {
                                        zip.file('etc/scm-' + integration + '.properties') { Writer writer ->
                                            def map = replaceBasedirForProperties(
                                                project,
                                                framework,
                                                scmconfig.getProperties()
                                            )

                                            def scmProps = map as Properties
                                            def sw = new StringWriter()
                                            scmProps.store(sw, "Exported configuration")
                                            def scmPropertiesText = sw.toString().
                                                    split(Pattern.quote(System.getProperty("line.separator"))).
                                                    sort().
                                                    join(System.getProperty("line.separator"))
                                            writer.write(scmPropertiesText)
                                        }

                                    }
                                }
                                listener?.inc('export', 1)
                            }
                        }
                    }
                }else if(enabledComponents[compName]) {
                    ProjectComponent exporter = enabledComponents[compName]
                    exporter.export(projectName, zip, options.exportOpts ? options.exportOpts[exporter.name] : [:])
                    listener?.inc('export', 1)
                }
            }
        }
        listener?.done()

    }

    public String getFilesystemProjectsBasedir(IFramework framework, IRundeckProject project) {
        if(framework instanceof IFilesystemFramework) {
            return new File(framework.getFrameworkProjectsBaseDir(), project.name).absolutePath
        }else{
            return null
        }
    }

    Map<String, String> replaceInitialStringInValues(
        final Map<String, String> projectProperties,
        String string,
        String replacement
    )
    {
        def Map<String, String> newmap = [:]

        projectProperties.each{k,v->
            if(v.startsWith(string)){
                newmap[k]= v.replaceFirst(Pattern.quote(string), Matcher.quoteReplacement(replacement))
            }else{
                newmap[k]=v
            }
        }
        newmap
    }

    Map<String, String> replacePlaceholderForProperties(
            final IRundeckProject project,
            final IFramework framework,
            final Properties projectProperties
    )
    {
        def map = new HashMap<String,String>()
        map.putAll(projectProperties as Map)
        return replacePlaceholderForProperties(project, framework, map)
    }
    Map<String, String> replaceBasedirForProperties(
            final IRundeckProject project,
            final IFramework framework,
            final Map<String, String> projectProperties
    )
    {
        def Map<String, String> newmap = [:]
        def basedir = getFilesystemProjectsBasedir(framework, project)
        if(basedir) {
            newmap = replaceInitialStringInValues(projectProperties, basedir, PROJECT_BASEDIR_PROPS_PLACEHOLDER)
        }
        newmap
    }
    Map<String, String> replacePlaceholderForProperties(
            final IRundeckProject project,
            final IFramework framework,
            final Map<String, String> projectProperties
    )
    {
        def Map<String, String> newmap = [:]
        def basedir = getFilesystemProjectsBasedir(framework, project)
        if(basedir) {
            newmap = replaceInitialStringInValues(projectProperties, PROJECT_BASEDIR_PROPS_PLACEHOLDER, basedir)
        }
        newmap
    }
    /**
     * Import a zip project archive to the project
     * @param project project
     * @param  user username of job owner
     * @param roleList role list string for scheduled jobs
     * @param framework framework
     * @param authContext authentication context
     * @param input input stream of zip data
     * @param options import options, [jobUUIDBehavior: (replace/preserve), importExecutions: (true/false)]
     */
    def importToProject(
            IRundeckProject project,
            IFramework framework,
            UserAndRolesAuthContext authContext,
            InputStream input,
            ProjectArchiveImportRequest options
    ) throws ProjectServiceException
    {
        ZipReader zip = new ZipReader(new ZipInputStream(input))
//        zip.debug=true
        def jobxml = []
        def jobxmlmap = [:]
        def execxml = []
        def execxmlmap = [:]
        def Map<String, File> execout = [:]
        def reportxml = []
        def reportxmlnames = [:]
        List<File> jfrecords = []
        Map<File, String> jfrecordnames = [:]
        boolean importExecutions = options.importExecutions
        boolean importConfig = options.importConfig
        boolean importACL = options.importACL
        boolean importScm = options.importScm
        boolean validateJobref = options.validateJobref
        boolean importNodes = options.importNodesSources

        File configtemp = null
        File scmimporttemp = null
        File scmexporttemp = null
        Map<String, Map<String,File>> importerstemp = new HashMap<>()
        Map<String, File> mdfilestemp = [:]
        Map<String, File> aclfilestemp = [:]

        Map<String, ProjectComponent> projectImporters = getProjectComponentsAuthorizedForImport(
            authContext,
            project.name
        )
        def baseRunBefore = [(BuiltinImportComponents.jobs.name()): [BuiltinImportComponents.executions.name()]]
        def sortOrder= BuiltinImportComponents.names() + (projectImporters?.keySet()?.toSorted() ?: [])

        def sortResult=Toposort.toposort(sortOrder, { String name->
            if(baseRunBefore[name]){
                return baseRunBefore[name]
            }
            return projectImporters[name]?.importMustRunBefore
        }, {String name->
            projectImporters[name]?.importMustRunAfter
        })
        if(sortResult.result){
            sortOrder=sortResult.result
        }else{
            //cyclic dependencies
            log.warn("Project Import: component ordering could not be determined due to cyclic dependency: ${sortResult.cycle}")
        }

        zip.read {
            '*/' { //rundeck-<projectname>/
                'jobs/' {
                    'job-.*\\.xml' { path, name, inputs ->
                        def tempfile = copyToTemp()
                        jobxml << tempfile
                        jobxmlmap[tempfile] = [path: path, name: name]
                    }
                }
                if (importExecutions) {
                    'executions/' {
                        'execution-.*\\.xml' { path, name, inputs ->
                            execxml << copyToTemp()
                            execxmlmap[execxml[-1]] = name
                        }
                        'output-.*\\.(txt|rdlog)|state-.*\\.state.json' { path, name, inputs ->
                            execout[name] = copyToTemp()
                        }
                    }
                    'jobfiles/' {
                        'filerecord-.*\\.xml' { path, name, inputs ->
                            jfrecords << copyToTemp()
                            jfrecordnames[jfrecords[-1]] = name
                        }
                    }
                    'reports/' {
                        'report-.*\\.xml' { path, name, inputs ->
                            reportxml << copyToTemp()
                            reportxmlnames[reportxml[-1]] = name
                        }
                    }
                }

                'files/' {
                    'etc/' {
                        if (importConfig || importNodes) {
                            'project.properties' { path, name, inputs ->
                                configtemp = copyToTemp()
                            }
                        }
                        if(importScm){
                            'scm-import.properties' { path, name, inputs ->
                                scmimporttemp = copyToTemp()
                            }
                            'scm-export.properties' { path, name, inputs ->
                                scmexporttemp = copyToTemp()
                            }
                        }
                    }
                    '(readme|motd)\\.md' { path, name, inputs ->
                        mdfilestemp[name] = copyToTemp()
                    }
                    if (importACL) {
                        'acls/' {
                            '.*\\.aclpolicy' { path, name, inputs ->
                                aclfilestemp[name] = copyToTemp()
                            }
                        }
                    }
                }
                if (projectImporters && options.importComponents) {
                    def builder = delegate
                    projectImporters.values().
                        findAll { options.importComponents[it.name] }.
                        each { ProjectComponent importer ->
                            Map<String, File> importFileMap = new HashMap<>()
                            importerstemp.put(importer.name, importFileMap)
                            importer.importFilePatterns.each { pattern ->
                                def parts = pattern.split('/')
                                builder.dirs(parts) { String path, String name, inputs ->
                                    def matched=(path+name).split('/',2)
                                    importFileMap.put(matched[1], copyToTemp())
                                }
                            }
                        }
                }
            }
        }


        //have files in dir
        (jobxml + execxml +
         execout.values() +
         reportxml +
         jfrecords +
         [configtemp] +
         [scmimporttemp, scmexporttemp] +
         mdfilestemp.values() +
         aclfilestemp.values()).
            each { it?.deleteOnExit() }
        importerstemp.values().each{it.values()*.deleteOnExit()}

        def loadjobresults = []
        def loadjoberrors = []
        def execerrors = []
        def jobIdMap = [:]
        def jobsByOldId = [:]
        def skipJobIds = []
        def aclerrors = []
        def scmerrors = []
        def importerErrors = [:]
        def projectName = project.name

        sortOrder.each { String sortKey ->

            if (sortKey == BuiltinImportComponents.jobs.name()) {
                //load jobs
                jobxml.each { File jxml ->
                    def path = jobxmlmap[jxml].path
                    def name = jobxmlmap[jxml].name
                    def jobset
                    jxml.withInputStream {
                        try {
                            def reader = new InputStreamReader(it, "UTF-8")
                            jobset = rundeckJobDefinitionManager.decodeFormat('xml', reader)
                        } catch (JobDefinitionException e) {
                            log.error("Failed parsing jobs from XML at archive path: ${path}${name}")
                            loadjoberrors << "Job XML file at archive path: ${path}${name} had errors: ${e.message}"
                            return
                        }
                        if (null == jobset) {
                            log.error("failed decoding jobs xml from zip: ${path}${name}")
                            return [errorCode: 'api.error.jobs.import.empty']
                        }
                        //contains list of old extids in input order
                        def oldids = jobset.collect { it.job.extid }
                        //change project name to the current project
                        jobset.each { it.job.project = projectName }
                        //remove uuid to reset it
                        def uuidBehavior = options.jobUuidOption ?: 'preserve'
                        switch (uuidBehavior) {
                            case 'remove':
                                jobset.each { it.job.uuid = null }
                                break;
                            case 'preserve':
                                //no-op, leave UUIDs and attempt to import
                                break;
                                break;
                        }
                        def results = scheduledExecutionService.loadImportedJobs(
                            jobset,
                            'update',
                            null,
                            [:],
                            authContext,
                            validateJobref
                        )

                        scheduledExecutionService.issueJobChangeEvents(results.jobChangeEvents)

                        if (results.errjobs) {
                            log.error(
                                "Failed loading (${results.errjobs.size()}) jobs from XML at archive path: ${path}${name}"

                            )
                            results.errjobs.each {
                                loadjoberrors << "Job at index [${it.entrynum}] at archive path: ${path}${name} had " +
                                "errors: ${it.errmsg}"

                                log.error("Job at index [${it.entrynum}] had errors: ${it.errmsg}")
                                if (it.entrynum != null && oldids[it.entrynum - 1]) {
                                    skipJobIds << oldids[it.entrynum - 1]
                                }
                            }
                        }
                        loadjobresults.addAll(results.jobs)
                        results.jobsi.each { jobi ->
                            if (jobi.entrynum != null && oldids[jobi.entrynum - 1]) {
                                jobIdMap[oldids[jobi.entrynum - 1]] = jobi.scheduledExecution.extid
                                jobsByOldId[oldids[jobi.entrynum - 1]] = jobi.scheduledExecution
                            }
                        }
                    }
                }

                log.info("Loaded ${loadjobresults.size()} jobs")
            } else if (sortKey == BuiltinImportComponents.executions.name() && importExecutions) {

                Map execidmap = importExecutionsToProject(
                    execxml,
                    execout,
                    projectName,
                    framework,
                    jobIdMap,
                    skipJobIds,
                    execxmlmap,
                    execerrors
                )
                //load reports
                importReportsToProject(reportxml, jobsByOldId, reportxmlnames, execidmap, projectName, execerrors)
                importFileRecordsToProject(jfrecords, jobIdMap, jfrecordnames, execidmap, execerrors)

            } else if (sortKey == BuiltinImportComponents.config.name() && (importConfig || importNodes) && configtemp) {
                importProjectConfig(configtemp, project, framework, importConfig, importNodes)
                log.debug("${project.name}: Loaded project configuration from archive")
            } else if (sortKey == BuiltinImportComponents.readme.name() && importConfig && mdfilestemp) {
                importProjectMdFiles(mdfilestemp, project)
            } else if (sortKey == BuiltinImportComponents.acl.name() && importACL && aclfilestemp) {
                aclerrors = importProjectACLPolicies(aclfilestemp, project)
            } else if (sortKey == BuiltinImportComponents.scm.name() && importScm) {

                if (scmimporttemp) {
                    def hasConfig = scmService.projectHasConfiguredPlugin(project.name, 'import')
                    if (!hasConfig) {
                        scmerrors += importScmConfig(scmimporttemp, project, framework, authContext, 'import')
                        log.debug("${project.name}: Loaded scm import configuration from archive")
                    } else {
                        log.error("${project.name}: cannot import SCM import configuration, already configured")
                    }
                }
                if (scmexporttemp) {
                    def hasConfig = scmService.projectHasConfiguredPlugin(project.name, 'export')
                    if (!hasConfig) {
                        scmerrors += importScmConfig(scmexporttemp, project, framework, authContext, 'export')
                        log.debug("${project.name}: Loaded scm export configuration from archive")
                    } else {
                        log.error("${project.name}: cannot import SCM export configuration, already configured")
                    }
                }

            } else{
                if (projectImporters && projectImporters[sortKey] && options.importComponents && options.importComponents[sortKey]){
                    ProjectComponent importer =projectImporters[sortKey]
                    if (importerstemp[importer.name]) {
                        try {
                            def result = importer.doImport(
                                authContext,
                                project.name,
                                importerstemp[importer.name],
                                options.importOpts[importer.name]
                            )
                            if (result) {
                                importerErrors[importer.name] = result
                            }
                        } catch (Throwable e) {
                            log.warn("Error during project import for ${importer.name}: $e.message")
                            log.debug("Error during project import for ${importer.name}: $e.message", e)
                            importerErrors[importer.name] = [e.message]
                        }
                    }

                }
            }
        }

        (jobxml + execxml + execout.values() + reportxml + [configtemp]+ [scmimporttemp,scmexporttemp] + mdfilestemp.values() + aclfilestemp.values()).
                each { it?.delete() }
        importerstemp.values().each{it.values()*.delete()}
        return [success: (loadjoberrors) ? false :
                true, joberrors: loadjoberrors, execerrors: execerrors, aclerrors: aclerrors, scmerrors: scmerrors, importerErrors: importerErrors]
    }
    static interface BeanProvider<T>{
        Map<String, T> getBeans()
    }
    static class SpringBeanProvider<T> implements BeanProvider<T>{
        ApplicationContext applicationContext
        Class<T> clazz

        @Override
        Map<String, T> getBeans() {
            return applicationContext.getBeansOfType(clazz)
        }
    }

    @CompileStatic
    public Map<String, ProjectComponent> getProjectComponents() {
        def types = componentBeanProvider.getBeans()
        Map<String, ProjectComponent> values = [:]
        types.each { k, v ->
            values[v.name] = v
        }
        values
    }
    @CompileStatic
    public Map<String, ProjectComponent> getProjectComponentsAuthorizedForImport(AuthContext authContext, String project) {
        //filter import components based on authorization
        def projectAuthResource = rundeckAuthContextEvaluator.authResourceForProject(project)
        Map<String, ProjectComponent> authorized = new HashMap<>()
        getProjectComponents()?.each { k, v ->
            if (!v.importAuthRequiredActions
                || rundeckAuthContextEvaluator.authorizeApplicationResourceAny(authContext, projectAuthResource, v.importAuthRequiredActions)) {
                authorized[k]=v
            }
        }
        authorized
    }
    @CompileStatic
    public Map<String, ProjectComponent> getProjectComponentsAuthorizedForExport(AuthContext authContext, String project) {
        //filter import components based on authorization
        def projectAuthResource = rundeckAuthContextEvaluator.authResourceForProject(project)
        Map<String, ProjectComponent> authorized = new HashMap<>()
        getProjectComponents()?.each { k, v ->
            if (!v.exportAuthRequiredActions
                || rundeckAuthContextEvaluator.authorizeApplicationResourceAny(authContext, projectAuthResource, v.exportAuthRequiredActions)) {
                authorized[k]=v
            }
        }
        authorized
    }

    @CompileStatic
    Map<String, Validator.Report> validateAllProjectComponentImportOptions(Map<String, Map<String, String>> options) {
        Map<String, Validator.Report> validations = [:]
        def components = getProjectComponents()
        options.keySet().each { name ->
            if (!name.contains('.') && (options[name] instanceof Map || options[name] == null) && components[name]) {
                validations[name] = validateProjectComponentImportOptions(name, options[name])
            }
        }
        validations
    }

    @CompileStatic
    Map<String, Validator.Report> validateAllProjectComponentExportOptions(Map<String, Map<String, String>> options) {
        Map<String, Validator.Report> validations = [:]
        def components = getProjectComponents()
        options.keySet().each { name ->
            if (!name.contains('.') && (options[name] instanceof Map || options[name] == null) && components[name]) {
                validations[name] = validateProjectComponentExportOptions(name, options[name])
            }
        }
        validations
    }

    @CompileStatic
    Validator.Report validateProjectComponentImportOptions(String name, Map<String, String> options) {
        Properties props = options as Properties
        def component = projectComponents[name]
        List<Property> importProperties = component.importProperties
        Validator.validate(props, importProperties)
    }

    @CompileStatic
    Validator.Report validateProjectComponentExportOptions(String name, Map<String, String> options) {
        Properties props = options as Properties
        def component = projectComponents[name]
        List<Property> importProperties = component.exportProperties
        Validator.validate(props, importProperties)
    }

    private List<String> importProjectACLPolicies(Map<String, File> aclfilestemp, IRundeckProject project) {
        def errors=[]
        aclfilestemp.each { String k, File v ->

            Validation validation = aclFileManagerService.
                forContext(AppACLContext.project(project.name)).
                validator.validateYamlPolicy('files/acls/'+k, v)
            if(!validation.valid){
                errors<<"files/acls/${k}: "+validation.toString()
                log.debug("${project.name}: Import failed for acls/${k}: "+validation)
                return
            }

            v.withInputStream { inputs ->
                aclFileManagerService.forContext(AppACLContext.project(project.name)).storePolicyFile(k, inputs)
                log.debug("${project.name}: Loaded project ACLPolicy file acl/${k} from archive")
            }
        }
        errors
    }

    private void importProjectMdFiles(Map<String, File> mdfilestemp, project) {
        mdfilestemp.each { String k, File v ->
            v.withInputStream { inputs ->
                project.storeFileResource(k, inputs)
                log.debug("${project.name}: Loaded project file ${k} from archive")
            }
        }
    }

    /**
     * Import a config file to the project, loaded from an archive
     * @param configtemp temp file containing config properties
     * @param project project
     * @param framework framework
     */
    private void importProjectConfig(File configtemp, IRundeckProject project, IFramework framework, boolean importConfig = true, boolean importNodes = true) {
        def inputProps = new Properties()
        configtemp.withReader { Reader reader ->
            inputProps.load(reader)
        }
        def map = replacePlaceholderForProperties(
            project,
            framework,
            inputProps
        )
        def newprops = new Properties()
        newprops.putAll(map)

        Properties propResourcesSource = newprops.findAll {String key, String value ->
                key.startsWith("resources.source.")
        }

        if(!importConfig && importNodes){//import only nodes
            project.mergeProjectProperties(propResourcesSource, ["resources.source."] as Set)
        } else if(importConfig) {
            if(!importNodes){//import all except the nodes
                newprops.removeAll {String key, String value -> propResourcesSource.keySet().contains(key)}
            }
            project.setProjectProperties(newprops)
        }
    }

    /**
     * Import a SCM config file to the project, loaded from an archive
     * @param configtemp temp file containing scm properties
     * @param project project
     * @param framework framework
     * @param auth
     * @param integration import or export
     */
    private List<String> importScmConfig(File configtemp, IRundeckProject project, IFramework framework, UserAndRolesAuthContext auth, String integration) {
        def inputProps = new Properties()
        configtemp.withReader { Reader reader ->
            inputProps.load(reader)
        }

        def map = replacePlaceholderForProperties(
            project,
            framework,
            inputProps
        )
        String type = map.get('scm.'+integration+'.type')

        def newprops = new Properties()
        map.each{k,v ->
            def prefix = 'scm.'+integration+'.config.'
            if(k.startsWith(prefix)){
                newprops.put((k-prefix),v)
            }
        }
        def result = scmService.savePluginSetup( auth,  integration,  project.name,  type, newprops)
        if (result.error || !result.valid) {
            def error = result.error ? result.message :"some input values were not valid"
            return ["SCM "+integration+": "+error]
        }
        []
    }

    /**
     * Import reports, and generate new reports for any executions with a missing report.
     * @param reportxml
     * @param jobsByOldId
     * @param reportxmlnames
     * @param execidmap
     * @param projectName
     */
    private void importReportsToProject(ArrayList reportxml, jobsByOldId, reportxmlnames, Map execidmap, projectName,loadjoberrors) {
        def loadedreports = []
        def execids = new ArrayList<Long>(execidmap.values())
        reportxml.each { rxml ->
            def report
            try {
                report = loadHistoryReport(rxml, execidmap, jobsByOldId, reportxmlnames[rxml])
            } catch (ProjectServiceException e) {
                loadjoberrors<<"[${reportxmlnames[rxml]}] ${e.message}"
                log.debug("[${reportxmlnames[rxml]}] ${e.message}",e)
                log.error("[${reportxmlnames[rxml]}] ${e.message}")
                return
            }
            if(!report){
                log.debug("[${reportxmlnames[rxml]}] Report skipped: no matching execution imported.")
                return
            }
            report.ctxProject = projectName
            if (!report.save()) {
                log.error("[${reportxmlnames[rxml]}] Unable to save report: ${report.errors}")
                return
            }
            execids.remove(Long.parseLong(report.jcExecId))
            loadedreports << report
        }
        //generate reports for executions without matching reports
        execids.each { eid ->
            Execution newe = Execution.get(eid)
            def report = ExecReport.fromExec(newe)
            if (!report.save()) {
                log.error("Unable to save generated report: ${report.errors} (execution ${eid})")
                return
            }
            loadedreports << report
        }
        log.info("Loaded ${loadedreports.size()} reports")
    }
    /**
     * Import job file records.
     * @param recordfiles
     * @param jobIdMap
     * @param recordfilenames
     * @param execidmap
     * @param projectName
     */
    private List<JobFileRecord> importFileRecordsToProject(
            ArrayList recordfiles,
            jobIdMap,
            recordfilenames,
            Map execidmap,
            loadjoberrors
    )
    {
        def loadedreports = []
        recordfiles.each { rxml ->
            def report
            try {
                report = loadJobFileRecord(rxml, execidmap, jobIdMap, recordfilenames[rxml])
            } catch (ProjectServiceException e) {
                loadjoberrors << "[${recordfilenames[rxml]}] ${e.message}"
                log.debug("[${recordfilenames[rxml]}] ${e.message}", e)
                log.error("[${recordfilenames[rxml]}] ${e.message}")
                return
            }
            if (!report) {
                log.debug("[${recordfilenames[rxml]}] File Record skipped: no matching execution imported.")
                return
            }
            if (!report.save()) {
                log.error("[${recordfilenames[rxml]}] Unable to save job file record: ${report.errors}")
                return
            }
            loadedreports << report
        }

        log.info("Loaded ${loadedreports.size()} file records: " + recordfilenames.values())
        loadedreports
    }

    /**
     * import executions, return a map from old execution ID to new ID
     * @param execxml
     * @param execout
     * @param projectName
     * @param framework
     * @param jobIdMap
     * @param skipJobIds list of Job IDs to skip execution import
     * @return map from old execution ID to new ID
     */
    private Map importExecutionsToProject(ArrayList execxml, Map<String, File> execout, projectName,
                                          IFramework framework, jobIdMap, skipJobIds, Map execxmlmap, execerrors = [] )
    {
        // map from old execution ID to new ID
        def execidmap = [:]
        def oldidtoexec = [:]
        def retryexecs= [:]
        def loadexecresults = []
        //load executions, and move/rewrite outputfile names
        execxml.each { File exml ->
            def results
            try {
                results = loadExecutions(exml,projectName, jobIdMap,skipJobIds)
            } catch (ProjectServiceException e) {
                log.debug("[${execxmlmap[exml]}] ${e.message}",e)
                execerrors<<"[${execxmlmap[exml]}] ${e.message}"
                return
            }
            def execlist = results.executions
            def oldids = results.execidmap
            retryexecs.putAll(results.retryidmap)
            execlist.each { Execution e ->
                e.project = projectName
                if (e.orchestrator && !e.orchestrator.save()) {
                    execerrors<<"[${execxmlmap[exml]}] Unable to save orchestrator for execution: ${e.orchestrator.errors}"
                    log.error("[${execxmlmap[exml]}] Unable to save orchestrator for execution: ${e.orchestrator.errors}")
                    return
                }
                if (e.workflow && !e.workflow.save()) {
                    execerrors<<"[${execxmlmap[exml]}] Unable to save workflow for execution: ${e.workflow.errors}"
                    log.error("[${execxmlmap[exml]}] Unable to save workflow for execution: ${e.workflow.errors}")
                    return
                }
                if (!e.save()) {
                    execerrors<<"[${execxmlmap[exml]}] Unable to save new execution: ${e.errors}"
                    log.error("[${execxmlmap[exml]}] Unable to save new execution: ${e.errors}")
                    return
                }
                loadexecresults << e
                if (oldids[e]) {
                    execidmap[oldids[e]] = e.id
                    oldidtoexec[oldids[e]]=e
                }
                //check outputfile exists in mapping
                if (e.outputfilepath && execout[e.outputfilepath]) {
                    File oldfile = execout[e.outputfilepath]
                    //move to appropriate location and update outputfilepath
                    File newfile = logFileStorageService.getFileForExecutionFiletype(
                            e,
                            LoggingService.LOG_FILE_FILETYPE,
                            false,
                            false
                    )
                    try{
                        FileUtils.moveFile(oldfile, newfile)
                    }catch (IOException exc) {
                        execerrors<<"Failed to move temp log file to destination: ${newfile.absolutePath} (old id ${oldids[e]}): ${exc.message}"
                        log.error("Failed to move temp log file to destination: ${newfile.absolutePath} (old id ${oldids[e]})", exc)
                    }
                    e.outputfilepath = newfile.absolutePath
                } else {
                    execerrors<<"New execution ${e.id}, NO matching outfile: ${e.outputfilepath}"
                    log.error("New execution ${e.id}, NO matching outfile: ${e.outputfilepath}")
                }

                //copy state.json file
                if(execout["state-${oldids[e]}.state.json"]){
                    File statefile= execout["state-${oldids[e]}.state.json"]
                    String filename = logFileStorageService.getFileForExecutionFiletype(
                            e,
                            WorkflowService.STATE_FILE_FILETYPE,
                            false,
                            false
                    )
                    File newfile = new File(filename)
                    try {
                        FileUtils.moveFile(statefile, newfile)
                    } catch (IOException exc) {
                        execerrors<<"Failed to move temp state file to destination: ${newfile.absolutePath} (old id ${oldids[e]}): ${exc.message}"
                        log.error("Failed to move temp state file to destination: ${newfile.absolutePath} (old id ${oldids[e]})", exc)
                    }
                }
            }
        }
        //reassign retry execution links
        loadexecresults.each { Execution e ->
            if (retryexecs[e]) {
                Execution retryExec = oldidtoexec[retryexecs[e]]
                if (retryExec) {
                    e.retryExecution = retryExec
                    if (!e.save()) {
                        execerrors<<"Unable to update execution retry link: ${e.errors} (Execution ${e.id})"
                        log.error("Unable to update execution retry link: ${e.errors} (Execution ${e.id})")
                        return
                    }
                }else{
                    execerrors<<"Failed to link retry for ${e.id} to ${retryexecs[e]}"
                    log.error("Failed to link retry for ${e.id} to ${retryexecs[e]}")
                }
            }
        }
        log.info("Loaded ${loadexecresults.size()} executions, map: ${execidmap}")
        execidmap
    }

    /**
     * Delete a project completely
     * @param project framework project
     * @param framework frameowkr
     * @return map [success:true/false, error: (String errorMessage)]
     */
    def deleteProject(IRundeckProject project, Framework framework, AuthContext authContext, String username){
        def result = [success: false]
        notify('projectWillBeDeleted', project.name)

        //disable scm
        scmService.removeAllPluginConfiguration(project.name)

        BaseReport.withTransaction { TransactionStatus status ->

            try {
                //delete all reports
                BaseReport.deleteByCtxProject(project.name)
                ExecReport.deleteByCtxProject(project.name)

                //delete all jobs with their executions
                ScheduledExecution.findAllByProject(project.name).each{ se->
                    def sedresult=scheduledExecutionService.deleteScheduledExecution(se, true, authContext,username)
                    if(!sedresult.success){
                        throw new Exception(sedresult.error)
                    }
                }
                //delete all remaining executions
                def allexecs= Execution.findAllByProject(project.name)
                def other=allexecs.size()
                executionService.deleteBulkExecutionIds(allexecs*.id, authContext, username)
                log.debug("${other} other executions deleted")


                fileUploadService.deleteRecordsForProject(project.name)

                def compErrors = []
                getProjectComponents()?.values()?.each {
                    try {
                        it.projectDeleted(project.name)
                    } catch (Throwable t) {
                        log.warn("Component ${it.name} had an error", t)
                        compErrors << "Component ${it.name} had an error: $t.message"
                    }
                }
                if (compErrors) {
                    throw new Exception("Some components had an error: " + compErrors.join("; "))
                }

                result = [success: true]
            } catch (Exception e) {
                status.setRollbackOnly()
                log.error("Failed to delete project ${project.name}", e)
                result = [error: "Failed to delete project ${project.name}: ${e.message}", success: false]
            }
        }
        //if success, delete framework dir
        if(result.success){
            framework.getFrameworkProjectMgr().removeFrameworkProject(project.name)
            notify('projectWasDeleted', project.name)
        } else {
            notify('projectDeleteFailed', project.name)
        }
        return result
    }

    boolean hasAclReadAuth(AuthContext authContext, String project) {
        rundeckAuthContextEvaluator.authorizeApplicationResourceAny(
            authContext,
            rundeckAuthContextEvaluator.authResourceForProjectAcl(project),
            [AuthConstants.ACTION_READ, AuthConstants.ACTION_ADMIN]
        )
    }

    boolean hasScmConfigure(AuthContext authContext, String project) {
        rundeckAuthContextEvaluator.authorizeApplicationResourceAny(
            authContext,
            rundeckAuthContextEvaluator.authResourceForProject(project),
            [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
        )
    }
}

@ToString(includeNames = true, includePackage = false)
class ArchiveOptions implements ProjectArchiveExportRequest{
    Set executionIds=null
    /**
     * if true, only include the executions in the executionIds set
     */
    boolean executionsOnly=false
    boolean all = false
    boolean jobs = false
    boolean executions = false
    boolean configs = false
    boolean readmes = false
    boolean acls = false
    boolean scm = false
    String stripJobRef = null
    Map<String, Map<String, String>> exportOpts = [:]
    Map<String, Boolean> exportComponents = [:]

    def parseExecutionsIds(execidsparam){
        if(execidsparam instanceof String){
            executionIds=new HashSet(execidsparam.split(',') as List)
        }else {
            executionIds=new HashSet([execidsparam].flatten())
        }
    }
}
class ArchiveRequest {
    String token
    Date dateStarted=new Date()
    volatile Throwable exception
    ProgressSummary summary
    volatile File file
    ImportResponse result
}
class ExportFileRequest extends ArchiveRequest{
    String instance
    String project
    String apitoken
}
class ImportResponse{
    volatile File file
    public boolean ok
    public List<String> errors
    public List<String> executionErrors
    public List<String> aclErrors
}
interface ProgressListener {
    void total(String key,long total)
    void inc(String key,long count)
    void done()
}
interface ProgressSummary {
    int percent()
}
class ArchiveRequestProgress implements ProgressSummary,ProgressListener{
    Map<String,Long> totals=new HashMap<String,Long>()
    Map<String,Long> counts=new HashMap<String,Long>()


    @Override
    void total(final String key, final long total) {
        this.totals[key]=total
        if(counts[key]==null){
            counts[key]=0
        }
    }

    @Override
    void inc(final String key, final long count) {
        this.counts[key]=this.counts[key]?this.counts[key]+count:count
    }

    @Override
    void done() {
        counts.putAll(totals)
    }

    @Override
    int percent() {
        Double sum=totals.keySet().inject(0){a,k->
            a + ( ( totals[k]>0 ? ( (counts[k]!=null?counts[k]:0d)/totals[k] ) : 1d) / totals.size() )
        }
        return Math.floor(100*sum)
    }
}

class ExportFileProgress extends ArchiveRequestProgress{
    @Override
    int percent() {
        Double sum=totals.keySet().inject(0){a,k->
            a + ( ( totals[k]>0 ? ( (counts[k]!=null?counts[k]:0d)/totals[k] ) : 1d) / totals.size() )
        }
        //50% generate file, 100% result api call
        return Math.floor(50*sum)
    }
}

class ProjectServiceException extends Exception {
    ProjectServiceException() {
    }

    ProjectServiceException(String s) {
        super(s)
    }

    ProjectServiceException(String s, Throwable throwable) {
        super(s, throwable)
    }

    ProjectServiceException(Throwable throwable) {
        super(throwable)
    }
}
