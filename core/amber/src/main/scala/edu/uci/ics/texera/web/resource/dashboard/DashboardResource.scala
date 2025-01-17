package edu.uci.ics.texera.web.resource.dashboard

import edu.uci.ics.texera.web.SqlServer
import edu.uci.ics.texera.web.auth.SessionUser
import edu.uci.ics.texera.web.model.jooq.generated.Tables.{USER, _}
import edu.uci.ics.texera.web.model.jooq.generated.enums.{
  UserFileAccessPrivilege,
  WorkflowUserAccessPrivilege
}
import edu.uci.ics.texera.web.model.jooq.generated.tables.pojos._
import edu.uci.ics.texera.web.resource.dashboard.DashboardResource._
import edu.uci.ics.texera.web.resource.dashboard.user.file.UserFileResource.DashboardFileEntry
import edu.uci.ics.texera.web.resource.dashboard.user.workflow.WorkflowResource._
import io.dropwizard.auth.Auth
import org.jooq.Condition
import org.jooq.impl.DSL
import org.jooq.impl.DSL.{falseCondition, noCondition}
import org.jooq.types.UInteger

import java.sql.Timestamp
import javax.ws.rs._
import javax.ws.rs.core.MediaType
import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`

/**
  * This file handles various requests that need to interact with multiple tables.
  */
object DashboardResource {
  final private lazy val context = SqlServer.createDSLContext()
  case class DashboardClickableFileEntry(
      resourceType: String,
      workflow: DashboardWorkflowEntry,
      project: Project,
      file: DashboardFileEntry
  )
}

@Produces(Array(MediaType.APPLICATION_JSON))
@Path("/dashboard")
class DashboardResource {

  /**
    * This method performs a full-text search in all resources(workflow, project, file)
    * that match the specified keywords.
    *
    * This method utilizes MySQL Boolean Full-Text Searches
    * reference: https://dev.mysql.com/doc/refman/8.0/en/fulltext-boolean.html
    *
    * @param sessionUser The authenticated user.
    * @param keywords    The search keywords.
    * @return A list of DashboardClickableFileEntry that match the search term.
    */
  @GET
  @Path("/search")
  def searchAllResources(
      @Auth sessionUser: SessionUser,
      @QueryParam("query") keywords: java.util.List[String],
      @QueryParam("resourceType") @DefaultValue("") resourceType: String = "",
      @QueryParam("createDateStart") @DefaultValue("") creationStartDate: String = "",
      @QueryParam("createDateEnd") @DefaultValue("") creationEndDate: String = "",
      @QueryParam("modifiedDateStart") @DefaultValue("") modifiedStartDate: String = "",
      @QueryParam("modifiedDateEnd") @DefaultValue("") modifiedEndDate: String = "",
      @QueryParam("owner") owners: java.util.List[String] = new java.util.ArrayList[String](),
      @QueryParam("id") workflowIDs: java.util.List[UInteger] = new java.util.ArrayList[UInteger](),
      @QueryParam("operator") operators: java.util.List[String] = new java.util.ArrayList[String](),
      @QueryParam("projectId") projectIds: java.util.List[UInteger] =
        new java.util.ArrayList[UInteger]()
  ): List[DashboardClickableFileEntry] = {
    val user = sessionUser.getUser
    // make sure keywords don't contain "+-()<>~*\"", these are reserved for SQL full-text boolean operator
    val splitKeywords = keywords.flatMap(word => word.split("[+\\-()<>~*@\"]+"))
    var workflowMatchQuery: Condition = noCondition()
    var projectMatchQuery: Condition = noCondition()
    var fileMatchQuery: Condition = noCondition()
    for (key: String <- splitKeywords) {
      if (key != "") {
        val words = key.split("\\s+")

        def getSearchQuery(subStringSearchEnabled: Boolean, resourceType: String): String = {
          resourceType match {
            case "workflow" =>
              "MATCH(texera_db.workflow.name, texera_db.workflow.description, texera_db.workflow.content) AGAINST(+{0}" +
                (if (subStringSearchEnabled) "'*'" else "") + " IN BOOLEAN mode)"
            case "project" =>
              "MATCH(texera_db.project.name, texera_db.project.description) AGAINST (+{0}" +
                (if (subStringSearchEnabled) "'*'" else "") + " IN BOOLEAN mode)"
            case "file" =>
              "MATCH(texera_db.file.name, texera_db.file.description) AGAINST (+{0}" +
                (if (subStringSearchEnabled) "'*'" else "") + " IN BOOLEAN mode) "
          }
        }

        if (words.length == 1) {
          // Use "*" to enable sub-string search.
          workflowMatchQuery = workflowMatchQuery.and(
            getSearchQuery(true, "workflow"),
            key
          )
          projectMatchQuery = projectMatchQuery.and(
            getSearchQuery(true, "project"),
            key
          )
          fileMatchQuery = fileMatchQuery.and(
            getSearchQuery(true, "file"),
            key
          )
        } else {
          // When the search query contains multiple words, sub-string search is not supported by MySQL.
          workflowMatchQuery = workflowMatchQuery.and(
            getSearchQuery(false, "workflow"),
            key
          )
          projectMatchQuery = projectMatchQuery.and(
            getSearchQuery(false, "project"),
            key
          )
          fileMatchQuery = fileMatchQuery.and(
            getSearchQuery(false, "file"),
            key
          )
        }
      }
    }

    // combine all filters with AND
    var workflowOptionalFilters: Condition = noCondition()
    workflowOptionalFilters = workflowOptionalFilters
      // Apply creation_time date filter
      .and(getDateFilter("creation", creationStartDate, creationEndDate, "workflow"))
      // Apply lastModified_time date filter
      .and(getDateFilter("modification", modifiedStartDate, modifiedEndDate, "workflow"))
      // Apply workflowID filter
      .and(getWorkflowIdFilter(workflowIDs))
      // Apply owner filter
      .and(getOwnerFilter(owners))
      // Apply operators filter
      .and(getOperatorsFilter(operators))
      // Apply projectId filter
      .and(getProjectFilter(projectIds, "workflow"))

    var projectOptionalFilters: Condition = noCondition()
    projectOptionalFilters = projectOptionalFilters
      .and(getDateFilter("creation", creationStartDate, creationEndDate, "project"))
      .and(getProjectFilter(projectIds, "project"))
      // apply owner filter
      .and(getOwnerFilter(owners))
      .and(
        // these filters are not available in project. If any of them exists, the query should return 0 project
        if (
          modifiedStartDate.nonEmpty || modifiedEndDate.nonEmpty || workflowIDs.nonEmpty || operators.nonEmpty
        ) falseCondition()
        else noCondition()
      )

    var fileOptionalFilters: Condition = noCondition()
    fileOptionalFilters = fileOptionalFilters
      .and(getDateFilter("creation", creationStartDate, creationEndDate, "file"))
      .and(getOwnerFilter(owners))
      .and(
        // these filters are not available in file. If any of them exists, the query should return 0 file
        if (
          modifiedStartDate.nonEmpty || modifiedEndDate.nonEmpty || workflowIDs.nonEmpty || operators.nonEmpty || projectIds.nonEmpty
        ) falseCondition()
        else noCondition()
      )

    /**
      * Refer to texera/core/scripts/sql/texera_ddl.sql to understand what each attribute is
      *
      * Common Attributes (4 columns): All types of resources have these 4 attributes
      * 1. `resourceType`: Represents the type of resource (`String`). Allowed value: project, workflow, file
      * 2. `name`: Specifies the name of the resource (`String`).
      * 3. `description`: Provides a description of the resource (`String`).
      * 4. `creation_time`: Indicates the timestamp of when the resource was created (`Timestamp`). It represents upload_time if the resourceType is `file`
      *
      * Workflow Attributes (5 columns): Only workflow will have these 5 attributes.
      * 5. `WID`: Represents the Workflow ID (`UInteger`).
      * 6. `lastModifiedTime`: Indicates the timestamp of the last modification made to the workflow (`Timestamp`).
      * 7. `privilege`: Specifies the privilege associated with the workflow (`Privilege`).
      * 8. `UID`: Represents the User ID associated with the workflow (`UInteger`).
      * 9. `userName`: Provides the name of the user associated with the workflow (`String`).
      *
      * Project Attributes (3 columns): Only project will have these 3 attributes.
      * 10. `pid`: Represents the Project ID (`UInteger`).
      * 11. `ownerId`: Indicates the ID of the project owner (`UInteger`).
      * 12. `color`: Specifies the color associated with the project (`String`).
      *
      * File Attributes (7 columns): Only files will have these 7 attributes.
      * 13. `ownerUID`: Represents the User ID of the file owner (`UInteger`).
      * 14. `fid`: Indicates the File ID (`UInteger`).
      * 15. `uploadTime`: Indicates the timestamp when the file was uploaded (`Timestamp`).
      * 16. `path`: Specifies the path of the file (`String`).
      * 17. `size`: Represents the size of the file (`UInteger`).
      * 18. `email`: Represents the email associated with the file owner (`String`).
      * 19. `userFileAccess`: Specifies the user file access privilege (`UserFileAccessPrivilege`).
      */

    // Retrieve workflow resource
    val workflowQuery =
      context
        .select(
          //common attributes: 4 columns
          DSL.inline("workflow").as("resourceType"),
          WORKFLOW.NAME,
          WORKFLOW.DESCRIPTION,
          WORKFLOW.CREATION_TIME,
          // workflow attributes: 5 columns
          WORKFLOW.WID,
          WORKFLOW.LAST_MODIFIED_TIME,
          WORKFLOW_USER_ACCESS.PRIVILEGE,
          WORKFLOW_OF_USER.UID,
          USER.NAME.as("userName"),
          // project attributes: 3 columns
          DSL.inline(null, classOf[UInteger]).as("pid"),
          DSL.inline(null, classOf[UInteger]).as("owner_id"),
          DSL.inline(null, classOf[String]).as("color"),
          // file attributes 7 columns
          DSL.inline(null, classOf[UInteger]).as("owner_uid"),
          DSL.inline(null, classOf[UInteger]).as("fid"),
          DSL.inline(null, classOf[Timestamp]).as("upload_time"),
          DSL.inline(null, classOf[String]).as("path"),
          DSL.inline(null, classOf[UInteger]).as("size"),
          DSL.inline(null, classOf[String]).as("email"),
          DSL.inline(null, classOf[UserFileAccessPrivilege]).as("user_file_access")
        )
        .from(WORKFLOW)
        .leftJoin(WORKFLOW_USER_ACCESS)
        .on(WORKFLOW_USER_ACCESS.WID.eq(WORKFLOW.WID))
        .leftJoin(WORKFLOW_OF_USER)
        .on(WORKFLOW_OF_USER.WID.eq(WORKFLOW.WID))
        .leftJoin(USER)
        .on(USER.UID.eq(WORKFLOW_OF_USER.UID))
        .leftJoin(WORKFLOW_OF_PROJECT)
        .on(WORKFLOW_OF_PROJECT.WID.eq(WORKFLOW.WID))
        .where(WORKFLOW_USER_ACCESS.UID.eq(user.getUid()))
        .and(
          workflowMatchQuery
        )
        .and(workflowOptionalFilters)

    // Retrieve project resource
    val projectQuery = context
      .select(
        //common attributes: 4 columns
        DSL.inline("project").as("resourceType"),
        PROJECT.NAME.as("name"),
        PROJECT.DESCRIPTION.as("description"),
        PROJECT.CREATION_TIME.as("creation_time"),
        // workflow attributes: 5 columns
        DSL.inline(null, classOf[UInteger]).as("wid"),
        DSL.inline(null, classOf[Timestamp]).as("last_modified_time"),
        DSL.inline(null, classOf[WorkflowUserAccessPrivilege]).as("privilege"),
        DSL.inline(null, classOf[UInteger]).as("uid"),
        DSL.inline(null, classOf[String]).as("userName"),
        // project attributes: 3 columns
        PROJECT.PID,
        PROJECT.OWNER_ID,
        PROJECT.COLOR,
        // file attributes 7 columns
        DSL.inline(null, classOf[UInteger]).as("owner_uid"),
        DSL.inline(null, classOf[UInteger]).as("fid"),
        DSL.inline(null, classOf[Timestamp]).as("upload_time"),
        DSL.inline(null, classOf[String]).as("path"),
        DSL.inline(null, classOf[UInteger]).as("size"),
        DSL.inline(null, classOf[String]).as("email"),
        DSL.inline(null, classOf[UserFileAccessPrivilege]).as("user_file_access")
      )
      .from(PROJECT)
      .join(USER)
      .on(PROJECT.OWNER_ID.eq(USER.UID))
      .where(PROJECT.OWNER_ID.eq(user.getUid()))
      .and(
        projectMatchQuery
      )
      .and(projectOptionalFilters)

    // Retrieve file resource
    val fileQuery = context
      .select(
        // common attributes: 4 columns
        DSL.inline("file").as("resourceType"),
        FILE.NAME,
        FILE.DESCRIPTION,
        DSL.inline(null, classOf[Timestamp]).as("creation_time"),
        // workflow attributes: 5 columns
        DSL.inline(null, classOf[UInteger]).as("wid"),
        DSL.inline(null, classOf[Timestamp]).as("last_modified_time"),
        DSL.inline(null, classOf[WorkflowUserAccessPrivilege]).as("privilege"),
        DSL.inline(null, classOf[UInteger]).as("uid"),
        DSL.inline(null, classOf[String]).as("userName"),
        // project attributes: 3 columns
        DSL.inline(null, classOf[UInteger]).as("pid"),
        DSL.inline(null, classOf[UInteger]).as("owner_id"),
        DSL.inline(null, classOf[String]).as("color"),
        // file attributes 7 columns
        FILE.OWNER_UID,
        FILE.FID,
        FILE.UPLOAD_TIME,
        FILE.PATH,
        FILE.SIZE,
        USER.EMAIL,
        USER_FILE_ACCESS.PRIVILEGE.as("user_file_access")
      )
      .from(USER_FILE_ACCESS)
      .join(FILE)
      .on(USER_FILE_ACCESS.FID.eq(FILE.FID))
      .join(USER)
      .on(FILE.OWNER_UID.eq(USER.UID))
      .where(USER_FILE_ACCESS.UID.eq(user.getUid()))
      .and(
        fileMatchQuery
      )
      .and(fileOptionalFilters)

    // Retrieve files to which all shared workflows have access
    val sharedWorkflowFileQuery = context
      .select(
        // common attributes: 4 columns
        DSL.inline("file").as("resourceType"),
        FILE.NAME,
        FILE.DESCRIPTION,
        DSL.inline(null, classOf[Timestamp]).as("creation_time"),
        // workflow attributes: 5 columns
        DSL.inline(null, classOf[UInteger]).as("wid"),
        DSL.inline(null, classOf[Timestamp]).as("last_modified_time"),
        DSL.inline(null, classOf[WorkflowUserAccessPrivilege]).as("privilege"),
        DSL.inline(null, classOf[UInteger]).as("uid"),
        DSL.inline(null, classOf[String]).as("userName"),
        // project attributes: 3 columns
        DSL.inline(null, classOf[UInteger]).as("pid"),
        DSL.inline(null, classOf[UInteger]).as("owner_id"),
        DSL.inline(null, classOf[String]).as("color"),
        // file attributes 7 columns
        FILE.OWNER_UID,
        FILE.FID,
        FILE.UPLOAD_TIME,
        FILE.PATH,
        FILE.SIZE,
        USER.EMAIL,
        DSL.inline(null, classOf[UserFileAccessPrivilege])
      )
      .from(FILE_OF_WORKFLOW)
      .join(FILE)
      .on(FILE_OF_WORKFLOW.FID.eq(FILE.FID))
      .join(USER)
      .on(FILE.OWNER_UID.eq(USER.UID))
      .join(WORKFLOW_USER_ACCESS)
      .on(FILE_OF_WORKFLOW.WID.eq(WORKFLOW_USER_ACCESS.WID))
      .where(WORKFLOW_USER_ACCESS.UID.eq(user.getUid()))
      .and(
        fileMatchQuery
      )
      .and(fileOptionalFilters)

    /**
      * If there is a need to make changes to `select` statement in any of the 4 subqueries above, make sure to make corresponding changes to the other 3 subqueries as well.
      * Synchronizing the changes across all subqueries is important because the columns in every SELECT statement must also be in the same order when using `union`.
      *
      * To synchronize the changes across all subqueries, follow these steps:
      *
      * 1. Identify the attribute or column that needs to be added or deleted in one of the subqueries.
      * 2. Locate the corresponding select statements in the other three subqueries that retrieve the same resource type.
      * 3. Update the select statements in the other subqueries to include the new attribute or exclude the deleted attribute, ensuring that the number and order of columns match the modified subquery.
      * 4. Verify that the modified select statements align with the desired response schema.
      *
      * You can use the sql query I wrote for testing in MySQL workbench.
      * SELECT
      * 'workflow' AS "resourceType",
      * WORKFLOW.NAME,
      * WORKFLOW.DESCRIPTION,
      * WORKFLOW.CREATION_TIME,
      * WORKFLOW.WID,
      * WORKFLOW.LAST_MODIFIED_TIME,
      * WORKFLOW_USER_ACCESS.PRIVILEGE,
      * WORKFLOW_OF_USER.UID,
      * USER.NAME as "userName",
      * null as pid,
      * null as owner_id,
      * null as color,
      * null as owner_uid,
      * null as fid,
      * null as upload_time,
      * null as path,
      * null as size,
      * null as email,
      * null as user_file_access
      * FROM
      * WORKFLOW
      * LEFT JOIN WORKFLOW_USER_ACCESS ON WORKFLOW_USER_ACCESS.WID = WORKFLOW.WID
      * LEFT JOIN WORKFLOW_OF_USER ON WORKFLOW_OF_USER.WID = WORKFLOW.WID
      * LEFT JOIN USER ON USER.UID = WORKFLOW_OF_USER.UID
      * LEFT JOIN WORKFLOW_OF_PROJECT ON WORKFLOW_OF_PROJECT.WID = WORKFLOW.WID
      * WHERE
      * WORKFLOW_USER_ACCESS.UID = 1 --make changes accordingly
      *
      * union
      * SELECT
      * 'project' AS "resourceType",
      * PROJECT.NAME,
      * PROJECT.DESCRIPTION,
      * PROJECT.CREATION_TIME,
      * null,
      * null,
      * null,
      * null,
      * null,
      * PROJECT.PID,
      * PROJECT.OWNER_ID,
      * PROJECT.COLOR,
      * null as owner_uid,
      * null as fid,
      * null as upload_time,
      * null as path,
      * null as size,
      * null as email,
      * null as user_file_access
      * FROM
      * PROJECT
      * WHERE
      * PROJECT.OWNER_ID = 1 --make changes accordingly
      *
      * union
      * SELECT
      * -- common attributes: 4 rows
      * 'file' AS "resourceType",
      * file.name,
      * file.description,
      * null,
      * -- workflow attributes: 5 rows
      * null,
      * null,
      * null,
      * null,
      * null,
      * -- project attributes: 3 rows
      * null,
      * null,
      * null,
      * -- file attributes 5 rows
      * file.owner_uid,
      * file.fid,
      * file.upload_time,
      * file.path,
      * file.size,
      * user.email,
      * USER_FILE_ACCESS.PRIVILEGE as user_file_access
      * FROM
      * USER_FILE_ACCESS
      * JOIN FILE ON USER_FILE_ACCESS.FID = FILE.FID
      * JOIN USER ON FILE.OWNER_UID = USER.UID
      * WHERE
      * USER_FILE_ACCESS.UID = 1 --make changes accordingly
      * union
      * SELECT
      * 'file' AS "resourceType",
      * file.name,
      * file.description,
      * null,
      * -- workflow attributes: 5 rows
      * null,
      * null,
      * null,
      * null,
      * null,
      * -- project attributes: 3 rows
      * null,
      * null,
      * null,
      * -- file attributes 5 rows:
      * file.owner_uid,
      * file.fid,
      * file.upload_time,
      * file.path,
      * file.size,
      * user.email,
      * null
      * FROM
      * FILE_OF_WORKFLOW
      * JOIN FILE ON FILE_OF_WORKFLOW.FID = FILE.FID
      * JOIN USER ON FILE.OWNER_UID = USER.UID
      * JOIN WORKFLOW_USER_ACCESS ON FILE_OF_WORKFLOW.WID = WORKFLOW_USER_ACCESS.WID
      * WHERE
      * WORKFLOW_USER_ACCESS.UID = 1; --make changes accordingly
      */
    // Combine all queries using union and fetch results
    val clickableFileEntry =
      resourceType match {
        case "workflow" => workflowQuery.fetch()
        case "project"  => projectQuery.fetch()
        case "file"     => fileQuery.union(sharedWorkflowFileQuery).fetch()
        case "" =>
          workflowQuery
            .union(projectQuery)
            .union(fileQuery)
            .union(sharedWorkflowFileQuery)
            .fetch()
        case _ =>
          throw new BadRequestException(
            "Unknown resourceType. Only 'workflow', 'project', and 'file' are allowed"
          )
      }

    clickableFileEntry
      .map(record => {
        val resourceType = record.get("resourceType", classOf[String])
        DashboardClickableFileEntry(
          resourceType,
          if (resourceType == "workflow") {
            DashboardWorkflowEntry(
              record.into(WORKFLOW_OF_USER).getUid.eq(user.getUid),
              record
                .into(WORKFLOW_USER_ACCESS)
                .into(classOf[WorkflowUserAccess])
                .getPrivilege
                .toString,
              record.into(USER).getName,
              record.into(WORKFLOW).into(classOf[Workflow]),
              List[UInteger]() // To do
            )
          } else {
            null
          },
          if (resourceType == "project") {
            record.into(PROJECT).into(classOf[Project])
          } else {
            null
          },
          if (resourceType == "file") {
            DashboardFileEntry(
              record.into(USER).getEmail,
              record.get(
                "user_file_access",
                classOf[UserFileAccessPrivilege]
              ) == UserFileAccessPrivilege.WRITE,
              record.into(FILE).into(classOf[File])
            )
          } else {
            null
          }
        )
      })
      .toList

  }

}
