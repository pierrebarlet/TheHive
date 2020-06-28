package org.thp.thehive.services

import akka.actor.ActorRef
import gremlin.scala._
import javax.inject.{Inject, Named, Singleton}
import org.thp.scalligraph.auth.{AuthContext, Permission}
import org.thp.scalligraph.models._
import org.thp.scalligraph.query.PropertyUpdater
import org.thp.scalligraph.services._
import org.thp.scalligraph.steps.StepsOps._
import org.thp.scalligraph.steps.{Traversal, VertexSteps}
import org.thp.scalligraph.{BadRequestError, EntitySteps, RichSeq}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.models._
import play.api.libs.json.JsObject

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

@Singleton
class OrganisationSrv @Inject() (
    roleSrv: RoleSrv,
    profileSrv: ProfileSrv,
    auditSrv: AuditSrv,
    @Named("integrity-check-actor") integrityCheckActor: ActorRef
)(
    implicit @Named("with-thehive-schema") db: Database
) extends VertexSrv[Organisation, OrganisationSteps] {

  val organisationOrganisationSrv = new EdgeSrv[OrganisationOrganisation, Organisation, Organisation]
  val organisationShareSrv        = new EdgeSrv[OrganisationShare, Organisation, Share]

  override def steps(raw: GremlinScala[Vertex])(implicit graph: Graph): OrganisationSteps = new OrganisationSteps(raw)

  override def createEntity(e: Organisation)(implicit graph: Graph, authContext: AuthContext): Try[Organisation with Entity] = {
    integrityCheckActor ! IntegrityCheckActor.EntityAdded("Organisation")
    super.createEntity(e)
  }

  def create(organisation: Organisation, user: User with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Organisation with Entity] =
    for {
      createdOrganisation <- create(organisation)
      _                   <- roleSrv.create(user, createdOrganisation, profileSrv.orgAdmin)
    } yield createdOrganisation

  def create(e: Organisation)(implicit graph: Graph, authContext: AuthContext): Try[Organisation with Entity] =
    for {
      createdOrganisation <- createEntity(e)
      _                   <- auditSrv.organisation.create(createdOrganisation, createdOrganisation.toJson)
    } yield createdOrganisation

  def current(implicit graph: Graph, authContext: AuthContext): OrganisationSteps = get(authContext.organisation)

  override def get(idOrName: String)(implicit graph: Graph): OrganisationSteps =
    if (db.isValidId(idOrName)) getByIds(idOrName)
    else initSteps.getByName(idOrName)

  override def exists(e: Organisation)(implicit graph: Graph): Boolean = initSteps.getByName(e.name).exists()

  override def update(
      steps: OrganisationSteps,
      propertyUpdaters: Seq[PropertyUpdater]
  )(implicit graph: Graph, authContext: AuthContext): Try[(OrganisationSteps, JsObject)] =
    if (steps.newInstance().has("name", Organisation.administration.name).exists())
      Failure(BadRequestError("Admin organisation is unmodifiable"))
    else {
      auditSrv.mergeAudits(super.update(steps, propertyUpdaters)) {
        case (orgSteps, updatedFields) =>
          orgSteps
            .newInstance()
            .getOrFail("Organisation")
            .flatMap(auditSrv.organisation.update(_, updatedFields))
      }
    }

  def linkExists(fromOrg: Organisation with Entity, toOrg: Organisation with Entity)(implicit graph: Graph): Boolean =
    fromOrg._id == toOrg._id || get(fromOrg).links.hasId(toOrg._id).exists()

  def link(fromOrg: Organisation with Entity, toOrg: Organisation with Entity)(implicit authContext: AuthContext, graph: Graph): Try[Unit] =
    if (linkExists(fromOrg, toOrg)) Success(())
    else organisationOrganisationSrv.create(OrganisationOrganisation(), fromOrg, toOrg).map(_ => ())

  def doubleLink(org1: Organisation with Entity, org2: Organisation with Entity)(implicit authContext: AuthContext, graph: Graph): Try[Unit] =
    if (org1.name == "admin" || org2.name == "admin") Failure(BadRequestError("Admin organisation cannot be link with other organisation"))
    else
      for {
        _ <- link(org1, org2)
        _ <- link(org2, org1)
      } yield ()

  def unlink(fromOrg: Organisation with Entity, toOrg: Organisation with Entity)(implicit graph: Graph): Try[Unit] =
    Success(
      get(fromOrg)
        .outToE[OrganisationOrganisation]
        .filter(_.otherV().hasId(toOrg._id))
        .remove()
    )

  def doubleUnlink(org1: Organisation with Entity, org2: Organisation with Entity)(implicit graph: Graph): Try[Unit] = {
    unlink(org1, org2) // can't fail
    unlink(org2, org1)
  }

  def updateLink(fromOrg: Organisation with Entity, toOrganisations: Seq[String])(implicit authContext: AuthContext, graph: Graph): Try[Unit] = {
    val (orgToAdd, orgToRemove) = get(fromOrg)
      .links
      .name
      .toIterator
      .foldLeft((toOrganisations.toSet, Set.empty[String])) {
        case ((toAdd, toRemove), o) if toAdd.contains(o) => (toAdd - o, toRemove)
        case ((toAdd, toRemove), o)                      => (toAdd, toRemove + o)
      }
    for {
      _ <- orgToAdd.toTry(getOrFail(_).flatMap(doubleLink(fromOrg, _)))
      _ <- orgToRemove.toTry(getOrFail(_).flatMap(doubleUnlink(fromOrg, _)))
    } yield ()
  }
}

@EntitySteps[Organisation]
class OrganisationSteps(raw: GremlinScala[Vertex])(implicit @Named("with-thehive-schema") db: Database, graph: Graph)
    extends VertexSteps[Organisation](raw) {

  def links: OrganisationSteps = newInstance(raw.outTo[OrganisationOrganisation])

  override def newInstance(newRaw: GremlinScala[Vertex]): OrganisationSteps = new OrganisationSteps(newRaw)

  def cases: CaseSteps = new CaseSteps(raw.outTo[OrganisationShare].outTo[ShareCase])

  def shares: ShareSteps = new ShareSteps(raw.outTo[OrganisationShare])

  def caseTemplates: CaseTemplateSteps = new CaseTemplateSteps(raw.inTo[CaseTemplateOrganisation])

  def users(requiredPermission: Permission): UserSteps = new UserSteps(
    raw
      .inTo[RoleOrganisation]
      .filter(_.outTo[RoleProfile].has(Key("permissions") of requiredPermission))
      .inTo[UserRole]
  )

  def pages: PageSteps = new PageSteps(raw.outTo[OrganisationPage])

  def alerts: AlertSteps = new AlertSteps(raw.inTo[AlertOrganisation])

  def dashboards: DashboardSteps = new DashboardSteps(raw.outTo[OrganisationDashboard])

  def visible(implicit authContext: AuthContext): OrganisationSteps =
    if (authContext.isPermitted(Permissions.manageOrganisation)) this
    else
      this.filter(_.visibleOrganisationsTo.users.has("login", authContext.userId))

  def richOrganisation: Traversal[RichOrganisation, RichOrganisation] =
    this
      .project(
        _.apply(By[Vertex]())
          .and(By(__[Vertex].outTo[OrganisationOrganisation].fold))
      )
      .map {
        case (organisation, linkedOrganisations) =>
          RichOrganisation(organisation.as[Organisation], linkedOrganisations.asScala.map(_.as[Organisation]))
      }

  def users: UserSteps = new UserSteps(raw.inTo[RoleOrganisation].inTo[UserRole])

  def userProfile(login: String): ProfileSteps =
    new ProfileSteps(
      this
        .inTo[RoleOrganisation]
        .filter(_.inTo[UserRole].has("login", login))
        .outTo[RoleProfile]
        .raw
    )

  def visibleOrganisationsTo: OrganisationSteps = new OrganisationSteps(raw.unionFlat(_.identity(), _.inTo[OrganisationOrganisation]).dedup())

  def visibleOrganisationsFrom: OrganisationSteps = new OrganisationSteps(raw.unionFlat(_.identity(), _.outTo[OrganisationOrganisation]).dedup())

  def config: ConfigSteps = new ConfigSteps(raw.outTo[OrganisationConfig])

  def get(idOrName: String): OrganisationSteps =
    if (db.isValidId(idOrName)) this.getByIds(idOrName)
    else getByName(idOrName)

  def getByName(name: String): OrganisationSteps = this.has("name", name)

  override def newInstance(): OrganisationSteps = new OrganisationSteps(raw.clone())
}

class OrganisationIntegrityCheckOps @Inject() (@Named("with-thehive-schema") val db: Database, val service: OrganisationSrv)
    extends IntegrityCheckOps[Organisation] {
  override def resolve(entities: List[Organisation with Entity])(implicit graph: Graph): Try[Unit] = entities match {
    case head :: tail =>
      tail.foreach(copyEdge(_, head))
      tail.foreach(service.get(_).remove())
      Success(())
    case _ => Success(())
  }
}
