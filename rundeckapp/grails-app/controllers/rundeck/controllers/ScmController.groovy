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

package rundeck.controllers

import com.dtolabs.rundeck.app.api.ApiVersions
import com.dtolabs.rundeck.app.api.CDataString
import com.dtolabs.rundeck.app.api.scm.*
import com.dtolabs.rundeck.app.support.ScheduledExecutionQuery
import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import com.dtolabs.rundeck.core.plugins.DescribedPlugin
import com.dtolabs.rundeck.core.plugins.configuration.Property
import com.dtolabs.rundeck.core.plugins.views.Action
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.*
import groovy.transform.PackageScope
import org.rundeck.app.authorization.AppAuthContextProcessor
import org.rundeck.core.auth.AuthConstants
import rundeck.ScheduledExecution

import javax.servlet.http.HttpServletResponse

class ScmController extends ControllerBase {
    def scmService
    def frameworkService
    AppAuthContextProcessor rundeckAuthContextProcessor
    def scheduledExecutionService

    def static allowedMethods = [
            disable                : ['POST'],
            enable                 : ['POST'],
            performActionSubmit    : ['POST'],

            apiPlugins             : ['GET'],
            apiPluginInput         : ['GET'],

            apiProjectSetup        : ['POST'],
            apiProjectConfig       : ['GET'],
            apiProjectStatus       : ['GET'],
            apiProjectEnable       : ['POST'],
            apiProjectDisable      : ['POST'],
            apiProjectActionInput  : ['GET'],
            apiProjectActionPerform: ['POST'],


            apiJobStatus           : ['GET'],
            apiJobDiff             : ['GET'],
            apiJobActionInput      : ['GET'],
            apiJobActionPerform    : ['POST'],
            deletePluginConfig     : ['POST'],
    ]
    /**
     * Require API v15 for all API endpoints
     */
    def beforeInterceptor = {
        if (actionName.startsWith('api')) {
            if (!apiService.requireVersion(request, response, ApiVersions.V15)) {
                return false
            }
        }
    }

    private validateCommandInput(Object input) {
        if (input.hasErrors()) {
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code  : 'api.error.invalid.request',
                    args  : [input.errors.allErrors.collect { g.message(error: it) }.join(",")]
            ]
            )
            return false
        }
        if (input.hasProperty('project')) {
            //verify project exists
            def project = input.project
            if (!frameworkService.existsFrameworkProject(project)) {

                apiService.renderErrorFormat(response, [
                        status: HttpServletResponse.SC_NOT_FOUND,
                        code  : 'api.error.item.doesnotexist',
                        args  : ["Project", project]
                ]
                )
                return false
            }
        }
        return true
    }

    private UserAndRolesAuthContext apiAuthorize(String project,  List<String> actions, String integration) {
        UserAndRolesAuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                session.subject,
                project
        )
        actions << AuthConstants.ACTION_ADMIN
        if (!apiService.requireAuthorized(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProject(project),
                        actions
                ),
                response,
                [integration, "Project", project] as Object[]
        )) {
            return null
        }
        return authContext
    }

    def apiPlugins(ScmIntegrationRequest apiPluginsRequest) {
        if (!validateCommandInput(apiPluginsRequest)) {
            return
        }
        def plugins = scmService.listPlugins(apiPluginsRequest.integration)
        def pluginConfig = scmService.loadScmConfig(apiPluginsRequest.project, apiPluginsRequest.integration)
        def eEnabled = pluginConfig?.enabled && scmService.projectHasConfiguredPlugin(apiPluginsRequest.integration, apiPluginsRequest.project)

        ScmPluginList list = new ScmPluginList(integration: apiPluginsRequest.integration)
        list.plugins = plugins.collect { k, DescribedPlugin describedPlugin ->
            new ScmPluginDescription(
                    type: describedPlugin.name,
                    title: describedPlugin.description.title,
                    description: CDataString.from(describedPlugin.description.description),
                    configured: describedPlugin.name == pluginConfig?.type,
                    enabled: describedPlugin.name == pluginConfig?.type && eEnabled,
                    )
        }

        respond list, [formats: ['xml', 'json']]
    }

    /**
     * /api/15/project/$project/scm/$integration/plugin/$type/input
     * @param pluginInputTypeReq
     * @return
     */
    def apiPluginInput(ScmPluginTypeRequest pluginInputTypeReq) {
        if (!validateCommandInput(pluginInputTypeReq)) {
            return
        }
        def isExport = pluginInputTypeReq.integration == 'export'
        def action = isExport ? AuthConstants.ACTION_EXPORT : AuthConstants.ACTION_IMPORT
        def scmAction = isExport ? AuthConstants.ACTION_SCM_EXPORT : AuthConstants.ACTION_SCM_IMPORT

        def authContext = apiAuthorize(pluginInputTypeReq.project, [action,scmAction], action)
        if (!authContext) {
            return
        }

        def properties = scmService.getSetupProperties(pluginInputTypeReq.integration, pluginInputTypeReq.project, pluginInputTypeReq.type).
                collect { Property prop -> fieldBeanForProperty(prop) }

        respond(
                new ScmPluginSetupInput(type: pluginInputTypeReq.type, integration: pluginInputTypeReq.integration, fields: properties),
                [formats: ['xml', 'json']]
        )
    }

    def index(String project) {
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, project)
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProject(project),
                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_CONFIGURE, 'Project', project
        )) {
            return
        }
        def ePluginConfig = scmService.loadScmConfig(project, 'export')
        def iPluginConfig = scmService.loadScmConfig(project, 'import')
        def eplugins = scmService.listPlugins('export')
        def iplugins = scmService.listPlugins('import')
        def eConfiguredPlugin = null
        def iConfiguredPlugin = null
        if (ePluginConfig?.type) {
            eConfiguredPlugin = scmService.getPluginDescriptor('export', ePluginConfig.type)
        }
        if (iPluginConfig?.type) {
            iConfiguredPlugin = scmService.getPluginDescriptor('import', iPluginConfig.type)
        }
        def eEnabled = ePluginConfig?.enabled && scmService.projectHasConfiguredPlugin('export', project)
        def iEnabled = iPluginConfig?.enabled && scmService.projectHasConfiguredPlugin('import', project)

        return [
                plugins         : [import: iplugins, export: eplugins],
                configuredPlugin: [import: iConfiguredPlugin, export: eConfiguredPlugin],
                pluginConfig    : [import: iPluginConfig, export: ePluginConfig],
                enabled         : [import: iEnabled, export: eEnabled]
        ]
    }

    def setup(String integration, String project, String type) {

        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, project)
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProject(project),
                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_CONFIGURE, 'Project', project
        )) {
            return
        }
        if (scmService.projectHasConfiguredPlugin(integration, project)) {
            return redirect(action: 'index', params: [project: project])
        }
        def describedPlugin = scmService.getPluginDescriptor(integration, type)
        def pluginConfig = scmService.loadScmConfig(project, integration)
        def config = [:]
        if (type == pluginConfig?.type) {
            config = pluginConfig.config
        }
        [
                properties : scmService.getSetupProperties(integration, project, type),
                type       : type,
                plugin     : describedPlugin,
                config     : config,
                integration: integration
        ]
    }

    def saveSetup(String integration, String project, String type) {

        UserAndRolesAuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                session.subject,
                project
        )
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProject(project),
                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_CONFIGURE, 'Project', project
        )) {
            return
        }

        def config = params.config

        boolean valid = false
        //cancel modification
        if (params.cancel == 'Cancel') {
            return redirect(controller: 'scm', action: 'index', params: [project: project])
        }

        withForm {
            valid = true
        }.invalidToken {
            request.errorCode = 'request.error.invalidtoken.message'
            renderErrorView([:])
        }
        if (!valid) {
            return
        }

        //require type param
        def result = scmService.savePluginSetup(authContext, integration, project, type, config)
        def report
        if (result.error || !result.valid) {
            report = result.report
            request.error = result.error ? result.message : message(code: "some.input.values.were.not.valid")
            def describedPlugin = scmService.getPluginDescriptor(integration, type)

            render view: 'setup',
                   model: [
                           properties : scmService.getSetupProperties(integration, project, type),
                           type       : type,
                           plugin     : describedPlugin,
                           report     : report,
                           config     : config,
                           integration: integration,
                   ]
        } else if (result.nextAction) {
            //redirect to next action
            flash.message = message(code: 'scmController.action.setup.success.message')
            redirect(
                    action: 'performAction',
                    params: [project: project, integration: integration, actionId: result.nextAction.id]
            )
        } else {
            flash.message = message(code: 'scmController.action.setup.success.message')
            redirect(action: 'index', params: [project: project])
        }
    }

    private def respondActionResult(IntegrationRequest scm, result, Map messages = [:]) {
        ScmActionResult actionResult
        def secondary = scm.hasProperty('type') ? scm.type : scm.hasProperty('actionId') ? scm.actionId : null
        def map = [formats: ['xml', 'json'],]
        if (result.error || !result.valid) {
            map.status = HttpServletResponse.SC_BAD_REQUEST

            def code = !result.valid ? messages.invalid ?: "some.input.values.were.not.valid" :
                    messages.error ?: 'some.input.values.were.not.valid'


            String errorMessage = result.error ? result.message :
                    message(code: code, args: [scm.integration, secondary])

            actionResult = new ScmActionResult(success: false, message: errorMessage)

            actionResult.validationErrors = result.report?.errors
        } else {
            String message = message(
                    code: messages.success ?: 'scmController.action.setup.success.message',
                    args: [scm.integration, secondary]
            )
            actionResult = new ScmActionResult(success: true, message: message, nextAction: result.nextAction?.id)
        }
        respond(actionResult, map)
    }
    /**
     * /api/15/project/$project/scm/$integration/plugin/$type/setup
     * @param scm
     * @return
     */
    def apiProjectSetup() {
        ScmPluginTypeRequest scm = new ScmPluginTypeRequest()
        bindData(scm, params)
        if (!validateCommandInput(scm)) {
            return
        }

        def authContext = apiAuthorize(scm.project, [AuthConstants.ACTION_CONFIGURE],AuthConstants.ACTION_CONFIGURE)
        if (!authContext) {
            return
        }
        ScmPluginConfig config = new ScmPluginConfig()
        String errormsg = ''
        def valid = apiService.parseJsonXmlWith(request, response, [
                json: { data ->
                    config.config = data.config
                    if (!data.config) {
                        errormsg += " json: expected 'config' property"
                    }
                },
                xml : { xml ->
                    def data = [:]
                    xml?.config?.entry?.each {
                        data[it.'@key'.text()] = it.text()
                    }
                    if (!data) {
                        errormsg += " xml: expected 'config' element: ${xml.config}"
                    } else {
                        config.config = data
                    }
                }
        ]
        )
        if (!valid) {
            return
        }

        if (null == config.config) {
            return respond(
                    new ScmActionResult(success: false, message: errormsg ?: 'Invalid format'),
                    [
                            formats: ['xml', 'json'],
                            status : HttpServletResponse.SC_BAD_REQUEST
                    ]
            )
        }

        //attempt to bind document body
        def configData = config.config

        def result = scmService.savePluginSetup(authContext, scm.integration, scm.project, scm.type, configData)
        respondActionResult(scm, result)
    }
    /**
     * /api/15/project/$project/scm/$integration/plugin/$type/disable
     * @param projDisableTypeReq
     * @return
     */
    def apiProjectDisable(ScmPluginTypeRequest projDisableTypeReq) {
        if (!projDisableTypeReq.project) {
            bindData(projDisableTypeReq, params)
        }
        if (!validateCommandInput(projDisableTypeReq)) {
            return
        }
        if (!apiAuthorize(projDisableTypeReq.project, [AuthConstants.ACTION_CONFIGURE],AuthConstants.ACTION_CONFIGURE)) {
            return
        }
        def result=[:]
        def ePluginConfig = scmService.loadScmConfig(projDisableTypeReq.project, projDisableTypeReq.integration)
        if (!ePluginConfig) {
            result = [
                    error  : true,
                    message: g.message(code: 'no.scm.integration.plugin.configured', args: [projDisableTypeReq.integration])
            ]
        } else if (ePluginConfig.type != projDisableTypeReq.type) {
            result = [
                    error  : true,
                    message: g.message(
                            code: 'integration.plugin.type.not.configured',
                            args: [projDisableTypeReq.type, projDisableTypeReq.integration]
                    )
            ]
        } else {
            scmService.disablePlugin(projDisableTypeReq.integration, projDisableTypeReq.project, projDisableTypeReq.type)
            result.valid=true
        }
        respondActionResult(
                projDisableTypeReq,
                result,
                [
                        error: 'scmController.action.disable.error.message',
                        success: 'scmController.action.disable.success.message'
                ]
        )
    }

    /**
     * /api/15/project/$project/scm/$integration/plugin/$type/enable
     * @param projEnableTypeReq
     * @return
     */
    def apiProjectEnable(ScmPluginTypeRequest projEnableTypeReq) {

        if (!projEnableTypeReq.project) {
            bindData(projEnableTypeReq, params)
        }
        if (!validateCommandInput(projEnableTypeReq)) {
            return
        }

        def auth = apiAuthorize(projEnableTypeReq.project, [AuthConstants.ACTION_CONFIGURE], AuthConstants.ACTION_CONFIGURE)
        if (!auth) {
            return
        }

        def result = scmService.enablePlugin(auth, projEnableTypeReq.integration, projEnableTypeReq.project, projEnableTypeReq.type)
        respondActionResult(projEnableTypeReq, result, [
                invalid: 'scmController.action.enable.invalid.message',
                error  : 'scmController.action.enable.error.message',
                success: 'scmController.action.enable.success.message'
        ]
        )
    }

    def disable(String integration, String project, String type) {

        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, project)
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProject(project),
                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_CONFIGURE, 'Project', project
        )) {
            return
        }

        boolean valid = false
        //cancel modification
        if (params.cancel == 'Cancel') {
            return redirect(controller: 'scm', action: 'index', params: [project: project])
        }

        withForm {
            valid = true
        }.invalidToken {
            request.errorCode = 'request.error.invalidtoken.message'
            renderErrorView([:])
        }
        if (!valid) {
            return
        }

        //require type param
        scmService.disablePlugin(integration, project, type)

        flash.message = message(code: "scmController.action.disable.success.message", args: [integration, type])
        redirect(action: 'index', params: [project: project])
    }

    def clean(String integration, String project, String type) {
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, project)
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProject(project),
                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_CONFIGURE, 'Project', project
        )) {
            return
        }

        boolean valid = false
        //cancel modification
        if (params.cancel == 'Cancel') {
            return redirect(controller: 'scm', action: 'index', params: [project: project])
        }

        withForm {
            valid = true
        }.invalidToken {
            request.errorCode = 'request.error.invalidtoken.message'
            renderErrorView([:])
        }
        if (!valid) {
            return
        }

        def result = scmService.cleanPlugin(integration, project, type,authContext)
        if(result && !result.valid){
            flash.error = result.message
        }else{
            flash.message = message(code: "scmController.action.clean.success.message", args: [integration, type])
        }
        redirect(action: 'index', params: [project: project])
    }

    def enable(String integration, String project, String type) {

        UserAndRolesAuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                session.subject,
                project
        )
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProject(project),
                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_CONFIGURE, 'Project', project
        )) {
            return
        }

        boolean valid = false
        //cancel modification
        if (params.cancel == 'Cancel') {
            return redirect(controller: 'scm', action: 'index', params: [project: project])
        }

        withForm {
            valid = true
        }.invalidToken {
            request.errorCode = 'request.error.invalidtoken.message'
            renderErrorView([:])
        }
        if (!valid) {
            return
        }

        //require type param
        def result = scmService.enablePlugin(authContext, integration, project, type)
        if (result.error) {
            flash.warn = message(
                    code: "scmController.action.enable.error.message",
                    args: [integration, type, result.message ?: 'unknown']
            )
            if (result.message) {
                flash.error = result.message
            }
        } else if (result.valid && result.nextAction) {
            //redirect to next action
            flash.message = message(code: "scmController.action.enable.success.message", args: [integration, type])
            return redirect(
                    action: 'performAction',
                    params: [project: project, integration: integration, actionId: result.nextAction.id]
            )
        } else if (result.valid) {
            flash.message = message(code: "scmController.action.enable.success.message", args: [integration, type])

        } else {
            flash.warn = message(code: "scmController.action.enable.invalid.message", args: [integration, type])
            if (result.message) {
                flash.error = result.message
            }
        }

        redirect(action: 'index', params: [project: project])
    }
    /**
     * /api/15/project/$project/scm/$integration/status
     */
    def apiProjectStatus(ScmIntegrationRequest apiProjStatusIntRequest) {
        if (!validateCommandInput(apiProjStatusIntRequest)) {
            return
        }

        boolean isExport = apiProjStatusIntRequest.integration == 'export'
        def action = isExport ? AuthConstants.ACTION_EXPORT : AuthConstants.ACTION_IMPORT
        def scmAction = isExport ? AuthConstants.ACTION_SCM_EXPORT : AuthConstants.ACTION_SCM_IMPORT

        def authContext = apiAuthorize(apiProjStatusIntRequest.project, [action,scmAction], action)
        if (!authContext) {
            return
        }

        if(frameworkService.isClusterModeEnabled()){
            //initialize if in another node
            scmService.initProject(params.project,apiProjStatusIntRequest.integration)
            if(isExport){
                def query=new ScheduledExecutionQuery()
                query.projFilter = params.project
                def jobs = scheduledExecutionService.listWorkflows(query, params)
                def jobsPluginMeta = scmService.getJobsPluginMeta(params.project, isExport)
                //relaod all jobs to get project status
                scmService.exportStatusForJobs(params.project, authContext, jobs.schedlist, true, jobsPluginMeta)
            }
        }

        if (!apiService.requireExists(response,
                                      scmService.projectHasConfiguredPlugin(apiProjStatusIntRequest.integration, apiProjStatusIntRequest.project),
                                      [apiProjStatusIntRequest.integration],
                                      "no.scm.integration.plugin.configured"
        )) {
            return
        }

        def scmProjectStatus = new ScmProjectStatus(integration: apiProjStatusIntRequest.integration, project: apiProjStatusIntRequest.project)

        try {
            if (isExport) {
                ScmExportSynchState status = scmService.exportPluginStatus(authContext, apiProjStatusIntRequest.project)
                List<Action> actions = scmService.exportPluginActions(authContext, apiProjStatusIntRequest.project)

                scmProjectStatus.synchState = status?.state?.toString()
                scmProjectStatus.message = status?.message
                scmProjectStatus.actions = actions?.collect { it.id }

            } else {

                ScmImportSynchState status = scmService.importPluginStatus(authContext, apiProjStatusIntRequest.project)
                List<Action> actions = scmService.importPluginActions(authContext, apiProjStatusIntRequest.project, status)

                scmProjectStatus.synchState = status?.state?.toString()
                scmProjectStatus.message = status?.message
                scmProjectStatus.actions = actions?.collect { it.id }
            }
        } catch (ScmPluginException e) {
            def message = message(
                    code: "api.scm.failed.to.get.scm.plugin.status",
                    args: [apiProjStatusIntRequest.integration, apiProjStatusIntRequest.project, e.message]
            )
            return respond(
                    new ScmActionResult(success: false, message: message),
                    [
                            formats: ['xml', 'json'],
                            status : HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    ]
            )
        }
        respond scmProjectStatus, [formats: ['xml', 'json']]
    }

    /**
     * /api/$api_version/project/$project/scm/$integration/config
     * @param apiProjConfigIntRequest
     * @return
     */
    def apiProjectConfig(ScmIntegrationRequest apiProjConfigIntRequest) {
        if (!validateCommandInput(apiProjConfigIntRequest)) {
            return
        }

        def authContext = apiAuthorize(apiProjConfigIntRequest.project, [AuthConstants.ACTION_CONFIGURE], AuthConstants.ACTION_CONFIGURE)
        if (!authContext) {
            return
        }

        def ePluginConfig = scmService.loadScmConfig(apiProjConfigIntRequest.project, apiProjConfigIntRequest.integration)
        if (!apiService.requireExists(
                response,
                ePluginConfig,
                [apiProjConfigIntRequest.integration],
                "no.scm.integration.plugin.configured"
        )) {
            return
        }


        def eEnabled = ePluginConfig.enabled && scmService.projectHasConfiguredPlugin(apiProjConfigIntRequest.integration, apiProjConfigIntRequest.project)

        def result = new ScmProjectPluginConfig(
                integration: apiProjConfigIntRequest.integration,
                project: apiProjConfigIntRequest.project,
                enabled: eEnabled,
                type: ePluginConfig.type,
                config: ePluginConfig.config
        )


        respond result, [formats: ['xml', 'json']]

    }

    /**
     * /api/15/project/$project/scm/$integration/action/$actionId/input
     * list inputs for action
     */
    def apiProjectActionInput(ScmActionRequest projActInputActReq) {
        if (!validateCommandInput(projActInputActReq)) {
            return
        }


        def isExport = projActInputActReq.integration == 'export'
        def action = isExport ? AuthConstants.ACTION_EXPORT : AuthConstants.ACTION_IMPORT
        def scmAction = isExport ? AuthConstants.ACTION_SCM_EXPORT : AuthConstants.ACTION_SCM_IMPORT

        def authContext = apiAuthorize(projActInputActReq.project, [action,scmAction], action)
        if (!authContext) {
            return
        }


        def view = scmService.getInputView(authContext, projActInputActReq.integration, projActInputActReq.project, projActInputActReq.actionId)

        if (!apiService.requireExists(
                response,
                view,
                [projActInputActReq.actionId, projActInputActReq.integration, projActInputActReq.project],
                "scm.not.a.valid.action.actionid"
        )) {
            return
        }
        return respondApiActionInput(view, projActInputActReq.project, projActInputActReq)

    }

    private Object respondApiActionInput(
            BasicInputView view,
            String project,
            ActionRequest scm,
            String jobId = null
    )
    {
        boolean isExport = scm.integration == 'export'
        def actionId = scm.actionId
        def integration = scm.integration

        /**
         *  map of [path: Map[id: jobid, jobNameAndGroup: string]]
         */

        List<ScmExportActionItem> exportActionItems = null
        if (isExport) {
            exportActionItems = getViewExportActionItems(project, jobId ? [jobId] : null)
        }

        /**
         * import: tracked items
         */
        List<ScmImportActionItem> importActionItems = null

        if (!isExport) {
            importActionItems = getViewImportItems(project, actionId, jobId ? [jobId] : null)
            importActionItems?.each { item ->
                item.status = ImportSynchState.IMPORT_NEEDED
                if(item.job){
                    if(item.job.jobId){
                        def se = ScheduledExecution.findByUuid(item.job.jobId)
                        if(se){
                            def status = scmService.importStatusForJob(se)
                            item.status = status?.get(item.job.jobId).synchState
                        }
                    }
                }
            }
        }

        //todo: project scm status
        //def scmProjectStatus = scmService.getPluginStatus(authContext, scm.integration, scm.project)

        def properties = view.properties.collect(this.&fieldBeanForProperty)

        respond(
                new ScmActionInput(
                        actionId: actionId,
                        integration: integration,
                        fields: properties,
                        title: view.title,
                        description: CDataString.from(view.description),
                        importItems: importActionItems,
                        exportItems: exportActionItems
                ),
                [formats: ['xml', 'json']]
        )
    }

    /**
     * Get export action items for project and optional jobs list
     * @param project
     * @param jobids
     * @return
     */
    @PackageScope
    ArrayList<ScmExportActionItem> getViewExportActionItems(String project, List<String> jobids = null) {
        Map<String, JobState> scmJobStatus
        List<ScmExportActionItem> exportActionItems = []
        Map deletedPaths = scmService.deletedExportFilesForProject(project)
        Map<String, String> renamedJobPaths = scmService.getRenamedJobPathsForProject(project)
        //remove deleted paths that are known to be renamed jobs
        renamedJobPaths.values().each {
            deletedPaths.remove(it)
        }
        List<ScheduledExecution> jobs = []
        Map<String, Map> jobPluginMeta = [:]
        if (jobids) {
            jobs = jobids.collect {
                ScheduledExecution.getByIdOrUUID(it)
            }.findAll { it }
        } else {
            jobs = ScheduledExecution.findAllByProject(project)
            jobPluginMeta = scmService.getJobsPluginMeta(project, true)
        }

        scmJobStatus = scmService.exportStatusForJobs(project, null, jobs, true, jobPluginMeta).findAll {k,v->
            v.synchState != SynchState.CLEAN
        }
        jobs = jobs.findAll {
            it.extid in scmJobStatus.keySet()
        }
        Map<String, String> scmFiles = scmService.exportFilePathsMapForJobs(project, jobs, jobPluginMeta)

        jobs.each { ScheduledExecution job ->
            ScmExportActionItem item = new ScmExportActionItem()
            item.job = new JobReference(jobId: job.extid, jobName: job.jobName, groupPath: job.groupPath)
            item.itemId = scmFiles[job.extid]
            item.originalId = renamedJobPaths[job.extid]
            item.renamed = null != item.originalId
            item.status = scmJobStatus.get(job.extid)?.synchState
            exportActionItems << item
        }
        deletedPaths.each { String path, Map jobInfo ->
            ScmExportActionItem item = new ScmExportActionItem()
            item.job = new JobReference(jobId: jobInfo.id, jobName: jobInfo.jobName, groupPath: jobInfo.groupPath)
            item.itemId = path
            item.deleted = true
            exportActionItems << item
        }
        exportActionItems
    }

    private ArrayList<ScmImportActionItem> getViewImportItems(
            String project,
            String actionId,
            List<String> jobids = null
    )
    {
        List<ScmImportActionItem> importActionItems = []
        List<ScmImportTrackedItem> trackingItems = []
        if (jobids) {
            trackingItems = scmService.getTrackingItemsForAction(project, actionId).findAll {
                it.jobId && it.jobId in jobids
            }
        } else {
            trackingItems = scmService.getTrackingItemsForAction(project, actionId)
        }
        trackingItems.each {
            ScmImportActionItem item = new ScmImportActionItem()
            item.itemId = it.id
            ScheduledExecution job = ScheduledExecution.getByIdOrUUID(it.jobId)
            if (job) {
                item.job = new JobReference(jobId: job.extid, jobName: job.jobName, groupPath: job.groupPath)
            }
            item.tracked = null != item.job
            item.deleted = it.isDeleted()

            importActionItems << item
        }
        importActionItems
    }

    private ScmPluginInputField fieldBeanForProperty(Property prop) {
        def field = new ScmPluginInputField(
                name: prop.name,
                title: prop.title,
                type: prop.type.toString(),
                defaultValue: prop.defaultValue,
                description: CDataString.from(prop.description),
                required: prop.required,
                scope: prop.scope?.toString() ?: null,
                renderingOptions: prop.renderingOptions.collectEntries { k, v -> [(k): v.toString()] }
        )
        if (prop.type in ([Property.Type.Select, Property.Type.FreeSelect])) {
            field.values = prop.selectValues
        }
        field
    }

    private BasicInputView validateView(String project, ActionRequest scm) {
        def isExport = scm.integration == 'export'
        def action = isExport ? AuthConstants.ACTION_EXPORT : AuthConstants.ACTION_IMPORT
        def scmAction = isExport ? AuthConstants.ACTION_SCM_EXPORT : AuthConstants.ACTION_SCM_IMPORT

        def authContext = apiAuthorize(project, [action,scmAction], action)
        if (!authContext) {
            return
        }

        if (!apiService.requireExists(response,
                                      scmService.projectHasConfiguredPlugin(scm.integration, project),
                                      [scm.integration],
                                      "no.scm.integration.plugin.configured"
        )) {
            return null
        }

        def view = scmService.getInputView(authContext, scm.integration, project, scm.actionId)

        if (!apiService.requireExists(response,
                                      view,
                                      [scm.actionId, scm.integration, project],
                                      "scm.not.a.valid.action.actionid"
        )) {
            return null
        }
        return view
    }
    /**
     * /api/$api_version/project/$project/scm/$integration/action/$actionId
     * @return
     */
    def apiProjectActionPerform() {
        ScmActionRequest scm = new ScmActionRequest()
        bindData(scm,params)
        if (!validateCommandInput(scm)) {
            return
        }
        def isExport = scm.integration == 'export'

        def view = validateView(scm.project, scm)
        if (!view) {
            return
        }

        ScmAction actionInput = parseScmActionInput()
        if (!actionInput) {
            return
        }

        UserAndRolesAuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                session.subject,
                scm.project
        )

        def (LinkedHashMap<String, Object> result, LinkedHashMap<String, String> messages) = performScmAction(
                isExport,
                actionInput,
                scm.project,
                scm,
                authContext
        )
        respondActionResult(scm, result, messages)
    }

    private List performScmAction(
            boolean isExport,
            ScmAction actionInput,
            String project,
            ActionRequest scm,
            UserAndRolesAuthContext authContext
    )
    {
        def result
        def messages = [
                success: 'api.scm.action.integration.success.message'
        ]
        if (isExport) {
            //input job ids
            Set<String> exportJobIds = actionInput.jobIds.collect { it.trim() }.findAll { it }

            //add job ids determined from input paths
            List<ScheduledExecution> alljobs = ScheduledExecution.findAllByProject(project)
            Map<String, ScheduledExecution> jobMap = alljobs.collectEntries { [it.extid, it] }

            def jobsPluginMeta = scmService.getJobsPluginMeta(project, isExport)

            Map scmJobStatus = scmService.exportStatusForJobs(project, authContext, alljobs,false, jobsPluginMeta).findAll {
                it.value.synchState != SynchState.CLEAN
            }

            List<ScheduledExecution> uncleanJobs = jobMap.subMap(scmJobStatus.keySet()).values() as List

            Map<String, String> scmFiles = scmService.exportFilePathsMapForJobs(
                project,
                uncleanJobs,
                jobsPluginMeta
            )
            Map reversed = [:]
            scmFiles.each { k, v ->
                reversed[v] = k
            }
            actionInput.selectedItems?.each {
                if (reversed[it]) {
                    exportJobIds << reversed[it]
                }
            }

            //list of jobs selected to export
            List<ScheduledExecution> exportJobs = exportJobIds.collect { jobMap[it] }.findAll { it }

            Map<String, String> renamedJobPaths = scmService.getRenamedJobPathsForProject(project)
            //add deleted paths from renamed jobs
            renamedJobPaths.each { k, v ->
                if (exportJobIds.contains(k)) {
                    actionInput.deletedItems << v
                }
            }

            //store job ids of deleted jobs, from path
//            def deletePathsToJobIds = actionInput.deletedItems?.collectEntries {
//                [it, scmService.deletedJobForPath(scm.project, it)?.id]
//            }

            result = scmService.performExportAction(
                    scm.actionId,
                    authContext,
                    project,
                    actionInput.input,
                    exportJobs,
                    actionInput.deletedItems
            )
            if (result.missingUserInfoField) {
                messages.invalid = 'scmController.action.saveCommit.userInfoMissing.message'
                messages.invalidExt = 'scmController.action.saveCommit.userInfoMissing.errorHelp'
            }
        } else {
            //determine paths for tracked jobs
            if (actionInput.jobIds) {
                List<ScmImportTrackedItem> trackingItems = scmService.getTrackingItemsForAction(
                        project,
                        scm.actionId
                )
                trackingItems.findAll { it.jobId in actionInput.jobIds }.each {
                    if(it.deleted){
                        actionInput.deletedJobs << it.jobId
                    }else {
                        actionInput.selectedItems << it.id
                    }
                }
            }
            result = scmService.performImportAction(
                    scm.actionId,
                    authContext,
                    project,
                    actionInput.input,
                    actionInput.selectedItems,
                    actionInput.deletedJobs
            )
        }
        [result, messages]
    }

    private ScmAction parseScmActionInput(boolean inputOnly = false) {
        ScmAction actionInput
        String errormsg = ''
        boolean valid = apiService.parseJsonXmlWith(request, response, [
                json: { data ->
                    def invalid = ScmAction.validateJson(data, inputOnly)
                    if (invalid) {
                        errormsg += invalid
                        return
                    }
                    actionInput = ScmAction.parseWithJson(data)
                },
                xml : { xml ->
                    def invalid = ScmAction.validateXml(xml)
                    if (invalid) {
                        errormsg += invalid
                        return
                    }
                    actionInput = ScmAction.parseWithXml(xml)
                }
        ]
        )
        if (!valid) {
            return null
        }
        if (valid && null == actionInput) {
            return respond(
                    new ScmActionResult(success: false, message: errormsg ?: message(code: "invalid.format")),
                    [
                            formats: ['xml', 'json'],
                            status : HttpServletResponse.SC_BAD_REQUEST
                    ]
            )
        }
        actionInput
    }

    def performAction(String integration, String project, String actionId) {
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, project)


        def requiredAction = integration == 'export' ? AuthConstants.ACTION_EXPORT : AuthConstants.ACTION_IMPORT
        def requiredActionScm = integration == 'export' ? AuthConstants.ACTION_SCM_EXPORT : AuthConstants.ACTION_SCM_IMPORT
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(authContext,
                                                                 rundeckAuthContextProcessor.authResourceForProject(project),
                                                                 [
                                                                         AuthConstants.ACTION_ADMIN,
                                                                         requiredAction,
                                                                         requiredActionScm
                                                                 ]
                ),
                requiredAction, 'Project', project
        )) {
            return
        }
        if (!scmService.projectHasConfiguredPlugin(integration, project)) {
            flash.message = "No plugin for SCM ${integration} configured for project ${project}"
            return redirect(action: 'index', params: [project: project])
        }
        def pluginDesc = scmService.loadProjectPluginDescriptor(project, integration)
        def view = scmService.getInputView(authContext, integration, project, actionId)
        if (!view) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            renderErrorView(message(code: "scm.not.a.valid.action.actionid", args: [actionId, integration, project]))
        }
        List<String> jobIds = []
        Map deletedPaths = [:]
        List<String> selectedPaths = []
        Map<String, String> renamedJobPaths = scmService.getRenamedJobPathsForProject(params.project)
        if (params.id) {
            jobIds = [params.id].flatten()
        } else {
            jobIds = ScheduledExecution.createCriteria().list{
                eq('project', params.project)
                cache(false)
                projections {
                    property("uuid")
                }
            }
            if (integration == 'export') {
                deletedPaths = scmService.deletedExportFilesForProject(params.project)
            }
        }
        //remove deleted paths that are known to be renamed jobs
        renamedJobPaths.values().each {
            deletedPaths.remove(it)
        }
        List<ScheduledExecution> jobs = []
        def toDeleteItems = []
        def skipCleanItems = []

        def jobMap = [:]
        def scmStatus = []
        boolean isExport = integration == 'export'
        def jobsPluginMeta = scmService.getJobsPluginMeta(project, isExport)
        if (isExport) {
            if(actionId && !actionId.equals(scmService.getExportPushActionId(project))){
                jobs = jobIds.collect {
                    ScheduledExecution.getByIdOrUUID(it)
                }
                scmStatus = scmService.exportStatusForJobs(project, authContext, jobs, false, jobsPluginMeta).findAll {
                    it.value.synchState != SynchState.CLEAN
                }
                jobs = jobs.findAll {
                    it.extid in scmStatus.keySet()
                }
            }
        } else {
            jobIds.each {
                jobMap[it] = ScheduledExecution.getByIdOrUUID(it)
            }
            jobs = (jobMap.values() as List).findAll { it != null }
            scmStatus = scmService.importStatusForJobs(project, authContext, jobs, false, jobsPluginMeta)
        }

        def trackingItems = integration == 'import' ? scmService.getTrackingItemsForAction(project, actionId) : null

        def scmProjectStatus = scmService.getPluginStatus(authContext, integration, project)
        def scmFiles = integration == 'export' ? scmService.exportFilePathsMapForJobs(project, jobs, jobsPluginMeta) : null

        if(integration == 'import'){
            //separate files to import and to delete
            trackingItems.each { item ->
                if(item.jobId){
                    def tmpJob = jobMap[item.jobId]
                    if(tmpJob && scmStatus.get(tmpJob.extid) &&
                       scmStatus.get(tmpJob.extid).synchState?.toString() == 'DELETE_NEEDED'
                    ){
                        toDeleteItems.add(item)
                    }
                    if(tmpJob && scmStatus.get(tmpJob.extid) &&
                            scmStatus.get(tmpJob.extid).synchState?.toString() == 'CLEAN'
                    ){
                        skipCleanItems.add(item)
                    }
                }
            }
            trackingItems?.removeAll(toDeleteItems)
            if(skipCleanItems){
                trackingItems?.removeAll(skipCleanItems)
            }
        }

        [
                pluginDescription: pluginDesc,
                actionView      : view,
                jobs            : jobs,
                jobMap          : jobMap,
                scmStatus       : scmStatus,
                selected        : params.id ? jobIds : [],
                filesMap        : scmFiles,
                trackingItems   : trackingItems,
                toDeleteItems   : toDeleteItems,
                deletedPaths    : deletedPaths,
                selectedPaths   : selectedPaths,
                renamedJobPaths : renamedJobPaths,
                scmProjectStatus: scmProjectStatus,
                actionId        : actionId,
                integration     : integration
        ]
    }

    def performActionSubmit(String integration, String project, String actionId) {

        UserAndRolesAuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                session.subject,
                project
        )
        def requiredAction = integration == 'export' ? AuthConstants.ACTION_EXPORT :
                AuthConstants.ACTION_IMPORT
        def requiredActionScm = integration == 'export' ? AuthConstants.ACTION_SCM_EXPORT : AuthConstants.ACTION_SCM_IMPORT
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(authContext,
                                                                 rundeckAuthContextProcessor.authResourceForProject(project),
                                                                 [AuthConstants.ACTION_ADMIN, requiredAction, requiredActionScm]
                ),
                requiredAction, 'Project', project
        )) {
            return
        }

        if (!scmService.projectHasConfiguredPlugin(integration, project)) {
            return redirect(action: 'index', params: [project: project])
        }
        boolean valid = false
        //cancel modification
        if (params.cancel == 'Cancel') {
            return redirect(controller: 'scheduledExecution', action: 'index', params: [project: project])
        }

        withForm {
            valid = true
        }.invalidToken {
            request.errorCode = 'request.error.invalidtoken.message'
            renderErrorView([:])
        }
        if (!valid) {
            return
        }
        List<String> jobIds = [params.id].flatten().findAll { it }

        List<ScheduledExecution> jobs = jobIds.collect {
            ScheduledExecution.getByIdOrUUID(it)
        }
        List<String> deletePaths = [params.deletePaths].flatten().findAll { it }
        jobIds.each {
            if (params."renamedPaths.${it}") {
                deletePaths << params."renamedPaths.${it}"
            }
        }

        List<String> chosenTrackedItems = [params.chosenTrackedItem].flatten().findAll { it }
        List<String> jobIdsToDelete = [params.chosenDeleteItem].flatten().findAll { it }

        def deletePathsToJobIds = deletePaths.collectEntries { [it, scmService.deletedJobForPath(project, it)?.id] }
        def result
        boolean isExport = integration == 'export'
        if (isExport) {
            result = scmService.performExportAction(
                    actionId,
                    authContext,
                    project,
                    params.pluginProperties,
                    jobs,
                    deletePaths
            )
        } else {
            result = scmService.performImportAction(
                    actionId,
                    authContext,
                    project,
                    params.pluginProperties,
                    chosenTrackedItems,
                    jobIdsToDelete
            )
        }
        if (!result.valid || result.error) {
            def report = result.report
            if (result.missingUserInfoField) {
                request.errors = [result.message]
                request.error = message(code: "scmController.action.saveCommit.userInfoMissing.message")
                request.errorHelp = message(code: "scmController.action.saveCommit.userInfoMissing.errorHelp")
            } else {
                request.error = result.error ? result.message : message(code: "some.input.values.were.not.valid")

                if (result.extendedMessage) {
                    request.errorHelp = result.extendedMessage
                }
            }
            def jobMap = [:]
            def pluginDesc = scmService.loadProjectPluginDescriptor(project, integration)
            def deletedPaths = scmService.deletedExportFilesForProject(project)
            Map<String, String> renamedJobPaths = scmService.getRenamedJobPathsForProject(params.project)
            //remove deleted paths that are known to be renamed jobs
            renamedJobPaths.values().each {
                deletedPaths.remove(it)
            }
            def jobsPluginMeta = scmService.getJobsPluginMeta(project, isExport)
            def scmStatus = integration == 'export' ? scmService.exportStatusForJobs(project, authContext, jobs, false, jobsPluginMeta) : null
            def scmFiles = integration == 'export' ? scmService.exportFilePathsMapForJobs(project, jobs, jobsPluginMeta) : null

            def scmProjectStatus = scmService.getPluginStatus(authContext, integration, params.project)
            def trackingItems = integration == 'import' ? scmService.getTrackingItemsForAction(project, actionId) : null

            jobs.each{job->
                jobMap[job.extid]=job
            }
            render view: 'performAction',
                   model: [
                           actionView       : scmService.getInputView(authContext, integration, project, actionId),
                           jobs             : jobs,
                           jobMap           : jobMap,
                           scmStatus        : scmStatus,
                           selected         : params.id ? jobIds : [],
                           filesMap         : scmFiles,
                           trackingItems    : trackingItems,
                           selectedItems    : chosenTrackedItems,
                           report           : report,
                           config           : params.pluginProperties,
                           deletedPaths     : deletedPaths,
                           renamedJobPaths  : renamedJobPaths,
                           selectedPaths    : deletePaths,
                           scmProjectStatus : scmProjectStatus,
                           actionId         : actionId,
                           integration      : integration,
                           pluginDescription: pluginDesc,
                   ]
            return
        }
        if (integration == 'export') {
            def commitid = result.commitId
            if (result.message) {
                flash.message = result.message
                if (result.extendedMessage) {
                    flash.extendedMessage = result.extendedMessage
                }
            } else {
                def code = "scmController.action.export.multi.succeed.message"
                def jobIdent = ''
                if (jobs.size() == 1 && deletePaths.size() == 0) {
                    code = "scmController.action.export.succeed.message"
                    jobIdent = '{{Job ' + jobIds[0] + '}}'
                } else if (jobs.size() == 0 && deletePaths.size() == 1) {
                    code = "scmController.action.export.delete.succeed.message"
                    jobIdent = deletePathsToJobIds[deletePaths[0]] ?: ''
                }

                flash.message = message(
                        code: code,
                        args: [
                                commitid,
                                jobs.size() + deletePaths.size(),
                                jobIdent
                        ]
                )
            }
        } else {
            if (result.message) {
                flash.message = result.message
                if (result.extendedMessage) {
                    flash.extendedMessage = result.extendedMessage
                }
            } else {
                flash.message = message(
                        code: 'scmController.action.import.success',
                        args: [],
                        default: "SCM Import Successful"
                )
            }
        }
        redirect(action: 'jobs', controller: 'menu', params: [project: params.project])
    }

    /**
     * /api/$api_version/job/$id/scm/$integration/status
     */
    def apiJobStatus(ScmJobRequest jobStatJobReq) {
        if (!validateCommandInput(jobStatJobReq)) {
            return
        }
        ScheduledExecution scheduledExecution = ScheduledExecution.getByIdOrUUID(jobStatJobReq.id)
        if (!apiRequireJob(scheduledExecution, jobStatJobReq)) {
            return
        }

        def isExport = jobStatJobReq.integration == 'export'
        def action = isExport ? AuthConstants.ACTION_EXPORT : AuthConstants.ACTION_IMPORT
        def scmAction = isExport ? AuthConstants.ACTION_SCM_EXPORT : AuthConstants.ACTION_SCM_IMPORT

        def authContext = apiAuthorize(scheduledExecution.project, [action,scmAction], action)

        if (!authContext) {
            return
        }
        if(frameworkService.isClusterModeEnabled()){
            //initialize if in another node
            scmService.initProject(scheduledExecution.project,jobStatJobReq.integration)
        }

        if (!apiService.requireExists(response,
                                      scmService.projectHasConfiguredPlugin(
                                              jobStatJobReq.integration,
                                              scheduledExecution.project
                                      ),
                                      [jobStatJobReq.integration],
                                      "no.scm.integration.plugin.configured"
        )) {
            return
        }



        def scmJobStatus = new ScmJobStatus(
                integration: jobStatJobReq.integration,
                project: scheduledExecution.project,
                id: jobStatJobReq.id
        )

        try {
            loadJobStatus(isExport, scheduledExecution, jobStatJobReq, scmJobStatus)
        } catch (ScmPluginException e) {
            def message = message(
                    code: "api.scm.failed.to.get.scm.plugin.job.status",
                    args: [jobStatJobReq.integration, jobStatJobReq.id, e.message]
            )
            return respond(
                    new ScmActionResult(success: false, message: message),
                    [
                            formats: ['xml', 'json'],
                            status : HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    ]
            )
        }
        respond scmJobStatus, [formats: ['xml', 'json']]
    }

    private void loadJobStatus(
            boolean isExport,
            ScheduledExecution scheduledExecution,
            ScmJobRequest scm,
            ScmJobStatus scmJobStatus
    )
    {
        UserAndRolesAuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, scheduledExecution.project)
        if (isExport) {
            def scmExportStatusMap = scmService.exportStatusForJobs(scheduledExecution.project, authContext, [scheduledExecution])
            JobState scmStatus = scmExportStatusMap[scm.id]

            scmJobStatus.synchState = scmStatus?.synchState?.toString()
            scmJobStatus.message = scmStatus?.synchState ? g.message(
                    code: "scm.${scm.integration}.status.${scmStatus.synchState}.display.text"
            ) : null
            scmJobStatus.actions = scmStatus?.actions?.collect { it.id }

            if (scmStatus?.commit) {
                def commit1 = scmStatus.commit
                scmJobStatus.commit = buildCommit(commit1)
            }

        } else {

            def scmImportStatusMap = scmService.importStatusForJobs(scheduledExecution.project, authContext, [scheduledExecution])
            JobImportState scmStatus = scmImportStatusMap[scm.id]

            scmJobStatus.synchState = scmStatus?.synchState?.toString()
            scmJobStatus.message = scmStatus?.synchState ? g.message(
                    code: "scm.${scm.integration}.status.${scmStatus.synchState}.display.text"
            ) : null
            scmJobStatus.actions = scmStatus?.actions?.collect { it.id }
            if (scmStatus?.commit) {
                def commit = scmStatus.commit
                scmJobStatus.commit = buildCommit(commit)
            }
        }
    }

    private ScmCommit buildCommit(ScmCommitInfo commit) {
        new ScmCommit(
                commitId: commit.commitId,
                author: commit.author,
                message: commit.message,
                date: commit.date,
                info: commit.asMap()
        )
    }

    /**
     * /api/$api_version/job/$id/scm/$integration/diff
     */
    def apiJobDiff(ScmJobRequest jobDiffJobReq) {
        if (!validateCommandInput(jobDiffJobReq)) {
            return
        }
        ScheduledExecution job = ScheduledExecution.getByIdOrUUID(jobDiffJobReq.id)
        if (!apiRequireJob(job, jobDiffJobReq)) {
            return
        }

        def isExport = jobDiffJobReq.integration == 'export'
        def action = isExport ? AuthConstants.ACTION_EXPORT : AuthConstants.ACTION_IMPORT
        def scmAction = isExport ? AuthConstants.ACTION_SCM_EXPORT : AuthConstants.ACTION_SCM_IMPORT

        def authContext = apiAuthorize(job.project, [action,scmAction], action)
        if (!authContext) {
            return
        }

        if (!apiService.requireExists(response,
                                      scmService.projectHasConfiguredPlugin(
                                              jobDiffJobReq.integration,
                                              job.project
                                      ),
                                      [jobDiffJobReq.integration],
                                      "no.scm.integration.plugin.configured"
        )) {
            return
        }


        def scmJobDiff = new ScmJobDiff(
                integration: jobDiffJobReq.integration,
                project: job.project,
                id: jobDiffJobReq.id
        )

        try {
            //read current commit
            ScmJobStatus scmJobStatus = new ScmJobStatus()
            loadJobStatus(isExport, job, jobDiffJobReq, scmJobStatus)
            scmJobDiff.commit = scmJobStatus.commit

            //load diff
            ScmDiffResult diffResult = null
            if (isExport) {
                diffResult = scmService.exportDiff(job.project, job)
            } else {
                ScmImportDiffResult importdiff = scmService.importDiff(job.project, job)
                scmJobDiff.incomingCommit = buildCommit(importdiff?.incomingCommit)
                diffResult = importdiff
            }
            scmJobDiff.diffContent = CDataString.from(diffResult?.content)
        } catch (ScmPluginException e) {
            def message = message(
                    code: "api.scm.failed.to.get.scm.plugin.job.status",
                    args: [jobDiffJobReq.integration, jobDiffJobReq.id, e.message]
            )
            return respond(
                    new ScmActionResult(success: false, message: message),
                    [
                            formats: ['xml', 'json'],
                            status : HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    ]
            )
        }
        respond scmJobDiff, [formats: ['xml', 'json']]
    }

    /**
     * Require job exists and user has READ access
     * @param scheduledExecution job
     * @param scm request
     * @return true if successful
     */
    private def apiRequireJob(ScheduledExecution scheduledExecution, ScmJobRequest scm) {
        if (!apiService.requireExists(response, scheduledExecution, ['Job', scm.id])) {
            return false
        }
        UserAndRolesAuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(
                session.subject,
                scheduledExecution.project
        )
        if (!apiService.requireAuthorized(
                rundeckAuthContextProcessor.authorizeProjectJobAny(
                        authContext,
                        scheduledExecution,
                        [AuthConstants.ACTION_READ,AuthConstants.ACTION_VIEW],
                        scheduledExecution.project
                ),
                response,
                [AuthConstants.ACTION_READ, "Job", scm.id] as Object[]
        )) {
            return false
        }
        true
    }

    /**
     * /api/$api_version/job/$id/scm/$integration/action/$actionId/input
     * @param jobActInputActRequest
     * @return
     */
    def apiJobActionInput(ScmJobActionRequest jobActInputActRequest) {

        if (!validateCommandInput(jobActInputActRequest)) {
            return
        }
        ScheduledExecution scheduledExecution = ScheduledExecution.getByIdOrUUID(jobActInputActRequest.id)
        if (!apiRequireJob(scheduledExecution, jobActInputActRequest)) {
            return
        }

        def isExport = jobActInputActRequest.integration == 'export'
        def action = isExport ? AuthConstants.ACTION_EXPORT : AuthConstants.ACTION_IMPORT
        def scmAction = isExport ? AuthConstants.ACTION_SCM_EXPORT : AuthConstants.ACTION_SCM_IMPORT

        def authContext = apiAuthorize(scheduledExecution.project, [action,scmAction], action)
        if (!authContext) {
            return
        }

        def view = validateView(scheduledExecution.project, jobActInputActRequest)
        if (!view) {
            return
        }

        respondApiActionInput(view, scheduledExecution.project, jobActInputActRequest, jobActInputActRequest.id)
    }

    /**
     * /api/$api_version/job/$id/scm/$integration/action/$actionId
     */
    def apiJobActionPerform() {
        ScmJobActionRequest scm = new ScmJobActionRequest()
        bindData(scm, params)
        if (!validateCommandInput(scm)) {
            return
        }
        ScheduledExecution scheduledExecution = ScheduledExecution.getByIdOrUUID(scm.id)
        if (!apiRequireJob(scheduledExecution, scm)) {
            return
        }

        def isExport = scm.integration == 'export'
        def action = isExport ? AuthConstants.ACTION_EXPORT : AuthConstants.ACTION_IMPORT
        def scmAction = isExport ? AuthConstants.ACTION_SCM_EXPORT : AuthConstants.ACTION_SCM_IMPORT

        def authContext = apiAuthorize(scheduledExecution.project, [action,scmAction], action)
        if (!authContext) {
            return
        }

        def view = validateView(scheduledExecution.project, scm)
        if (!view) {
            return
        }

        ScmAction actionInput = parseScmActionInput(true)
        if (!actionInput) {
            return
        }
        actionInput.jobIds = [scm.id]
        actionInput.deletedItems = []
        actionInput.selectedItems = []

        def (Map<String, Object> result, Map<String, String> messages) = performScmAction(
                isExport,
                actionInput,
                scheduledExecution.project,
                scm,
                authContext
        )
        respondActionResult(scm, result, messages)
    }

    def diff(String project, String id, String integration) {
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, project)



        def isExport = integration == 'export'
        def diffAction = isExport ? AuthConstants.ACTION_EXPORT : AuthConstants.ACTION_IMPORT
        def diffScmAction = integration == 'export' ? AuthConstants.ACTION_SCM_EXPORT : AuthConstants.ACTION_SCM_IMPORT
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(authContext,
                                                                 rundeckAuthContextProcessor.authResourceForProject(project),
                                                                 [AuthConstants.ACTION_ADMIN, diffAction, diffScmAction]
                ),
                diffAction, 'Project', project
        )) {
            return
        }
        if (!scmService.projectHasConfiguredPlugin(integration, project)) {
            return redirect(action: 'index', params: [project: project])
        }
        if (!id) {
            flash.message = "No jobId Selected"
            return redirect(action: 'index', params: [project: project])
        }
        def job = ScheduledExecution.getByIdOrUUID(id)
        String type = isExport ? scmService.STORAGE_NAME_EXPORT : scmService.STORAGE_NAME_IMPORT
        def jobMetaMap = [(id):scmService.getJobPluginMeta(job, type)]
        def exportStatus = isExport ? scmService.exportStatusForJobs(project, authContext, [job], true, jobMetaMap) : null
        def importStatus = isExport ? null : scmService.importStatusForJobs(project, authContext, [job])
        def scmFilePaths = isExport ? scmService.exportFilePathsMapForJobs(project, [job]) : scmService.importFilePathsMapForJobs(project, [job])
        def diffResult = isExport ? scmService.exportDiff(project, job) : scmService.importDiff(project, job)

        def scmImportRenamedPath = null
        if(!isExport){
            JobRenamed scmImportRenamed = importStatus.get(job.extid)?.jobRenamed
            if(scmImportRenamed){
                scmImportRenamedPath = scmImportRenamed.renamedPath
            }
        }

        def scmExportRenamedPath = isExport ? scmService.getRenamedJobPathsForProject(params.project)?.get(job.extid) : null

        if (params.download == 'true') {
            if (params.download) {
                response.addHeader("Content-Disposition", "attachment; filename=\"${job.extid}.diff\"")
            }
            render(contentType: 'text/plain', text: diffResult?.content ?: '')
            return
        }
        [
                diffResult          : diffResult,
                scmExportStatus     : exportStatus,
                scmImportStatus     : importStatus,
                job                 : job,
                isScheduled         : scheduledExecutionService.isScheduled(job),
                scmFilePaths        : scmFilePaths,
                scmExportRenamedPath : scmExportRenamedPath,
                scmImportRenamedPath : scmImportRenamedPath,
                integration         : integration
        ]
    }

    def deletePluginConfig(String project, String integration, String type){
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubjectAndProject(session.subject, project)
        if (unauthorizedResponse(
                rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        authContext,
                        rundeckAuthContextProcessor.authResourceForProject(project),
                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_CONFIGURE, 'Project', project
        )) {
            return
        }

        if (params.cancel == 'Cancel') {
            return redirect(controller: 'scm', action: 'index', params: [project: project])
        }

        boolean valid = false
        withForm {
            valid = true
        }.invalidToken {
            request.errorCode = 'request.error.invalidtoken.message'
            renderErrorView([:])
        }
        if (!valid) {
            return
        }

        def result = scmService.cleanPlugin(integration, project, type, authContext)
        if(result && !result.valid){
            flash.error = result.message
        }else{
            def deleted = scmService.removePluginConfiguration(integration, project, type)

            if (deleted) {
                flash.message = message(code: "scmController.action.delete.success.message", args: [integration])
            }else{
                flash.error = message(code: "scmController.action.delete.error.message", args: [integration])
            }
        }

        redirect(action: 'index', params: [project: project])
    }

}
