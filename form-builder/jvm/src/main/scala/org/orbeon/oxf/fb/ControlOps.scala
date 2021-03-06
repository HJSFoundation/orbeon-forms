/**
 * Copyright (C) 2011 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fb

import org.orbeon.datatypes.Coordinate1
import org.orbeon.dom.QName
import org.orbeon.oxf.fb.UndoAction._
import org.orbeon.oxf.fr.FormRunner
import org.orbeon.oxf.fr.FormRunner._
import org.orbeon.oxf.fr.NodeInfoCell._
import org.orbeon.oxf.fr.XMLNames._
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.util.Whitespace
import org.orbeon.oxf.xforms.NodeInfoFactory._
import org.orbeon.oxf.xforms.XFormsConstants._
import org.orbeon.oxf.xforms.action.XFormsAPI
import org.orbeon.oxf.xforms.action.XFormsAPI._
import org.orbeon.oxf.xforms.analysis.ElementAnalysis
import org.orbeon.oxf.xforms.analysis.model.Model
import org.orbeon.oxf.xforms.control.XFormsControl
import org.orbeon.oxf.xml.SaxonUtils.parseQName
import org.orbeon.oxf.xml.XMLConstants.XS_STRING_QNAME
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.Implicits._
import org.orbeon.scaxon.NodeConversions._
import org.orbeon.scaxon.SimplePath._
import org.orbeon.xforms.XFormsId

import scala.annotation.tailrec
import scala.collection.mutable

/*
 * Form Builder: operations on controls.
 */
trait ControlOps extends SchemaOps with ResourcesOps {

  self: GridOps ⇒ // funky dependency, to resolve at some point

  private val MIPsToRewrite = Model.AllMIPs - Model.Type - Model.Required - Model.Whitespace
  private val RewrittenMIPs = MIPsToRewrite map (mip ⇒ mip → QName(mip.name, XMLNames.FBPrefix, XMLNames.FB)) toMap

  private val TopLevelBindTemplate: NodeInfo =
    <xf:bind
      id="fr-form-binds"
      ref="instance('fr-form-instance')"
      xmlns:xf="http://www.w3.org/2002/xforms"/>

  // Find data holders (there can be more than one with repeats)
  def findDataHolders(controlName: String)(implicit ctx: FormBuilderDocContext): List[NodeInfo] =
    findBindPathHoldersInDocument(ctx.formDefinitionRootElem, controlName, Some(ctx.dataRootElem)) flatMap (_.holders) getOrElse Nil

  def precedingBoundControlNameInSectionForControl(controlElem: NodeInfo): Option[String] = {

    val cell = controlElem parent CellTest head
    val grid = findAncestorContainersLeafToRoot(cell).head

    assert(cell.localname == "c")
    assert(grid.localname == "grid")

    val precedingCellsInGrid = cell precedingSibling CellTest

    def fromPrecedingNamesInGrid = precedingCellsInGrid flatMap (_ firstChildOpt * flatMap getControlNameOpt) headOption

    def fromPrecedingGrids =
      if (grid.hasAtt("bind"))
        None
      else
        precedingBoundControlNameInSectionForGrid(grid, includeSelf = false)

    fromPrecedingNamesInGrid orElse fromPrecedingGrids
  }

  def precedingBoundControlNameInSectionForGrid(gridElem: NodeInfo, includeSelf: Boolean): Option[String] = {

    // If a container has a `bind`, then use its name, otherwise it is an unbound grid so find its last control
    // with a name (there might not be one).
    val boundControls =
      precedingSiblingOrSelfContainers(gridElem, includeSelf) flatMap {
        case grid if ! grid.hasAtt("bind") ⇒ grid descendant CellTest child * filter hasName lastOption
        case boundSectionOrGrid            ⇒ Some(boundSectionOrGrid)
      }

    // Take the first result
    boundControls.headOption flatMap getControlNameOpt
  }

  // Ensure that a tree of bind exists
  def ensureBinds(names: Seq[String])(implicit ctx: FormBuilderDocContext): NodeInfo = {

    // Insert bind container if needed
    val topLevelBind = ctx.topLevelBindElem match {
      case Some(bind) ⇒
        bind
      case None ⇒
        insert(
          into   = ctx.modelElem,
          after  = ctx.dataInstanceElem,
          origin = TopLevelBindTemplate
        ).head
    }

    // Insert a bind into one level
    @tailrec def ensureBind(containerElem: NodeInfo, names: Iterator[String]): NodeInfo = {
      if (names.hasNext) {
        val bindName = names.next()
        val bind = containerElem / XFBindTest filter (isBindForName(_, bindName)) match {
          case Seq(bind: NodeInfo, _*) ⇒ bind
          case _ ⇒

            val newBind: Seq[NodeInfo] =
              <xf:bind
                id={bindId(bindName)}
                ref={bindName}
                name={bindName}
                xmlns:xf="http://www.w3.org/2002/xforms"/>

            insert(into = containerElem, after = containerElem / XFBindTest, origin = newBind).head
        }
        ensureBind(bind, names)
      } else
        containerElem
    }

    // Start with top-level
    ensureBind(topLevelBind, names.toIterator)
  }

  // Iterate over the given bind followed by all of its descendants, depth-first
  def iterateSelfAndDescendantBinds(rootBind: NodeInfo): Iterator[NodeInfo] =
    rootBind descendantOrSelf XFBindTest iterator

  def iterateSelfAndDescendantBindsReversed(rootBind: NodeInfo): Iterator[NodeInfo] =
    (rootBind descendantOrSelf XFBindTest).reverseIterator

  // Iterate over the given holder and descendants in reverse depth-first order
  def iterateSelfAndDescendantHoldersReversed(rootHolder: NodeInfo): Iterator[NodeInfo] =
    (rootHolder descendantOrSelf *).reverseIterator

  def deleteControlWithinCell(
    cellElem        : NodeInfo,
    updateTemplates : Boolean = false)(implicit
    ctx             : FormBuilderDocContext
  ): Option[UndoAction] =
    cellElem firstChildOpt * map { controlElem ⇒

      val undo =
        UndoDeleteControl(
          ControlPosition(
            gridName   = findAncestorContainersLeafToRoot(controlElem, includeSelf = false).headOption flatMap getControlNameOpt get,
            coordinate = Coordinate1(NodeInfoCellOps.x(cellElem).get, NodeInfoCellOps.y(cellElem).get)
          ),
          ToolboxOps.controlOrContainerElemToXcv(controlElem)
        )

      controlElementsToDelete(controlElem) foreach (delete(_))

      if (updateTemplates)
        self.updateTemplatesCheckContainers(findAncestorRepeatNames(cellElem).to[Set])(FormBuilderDocContext())

      undo
    }

  // Find all associated elements to delete for a given control element
  def controlElementsToDelete(controlElem: NodeInfo)(implicit ctx: FormBuilderDocContext): List[NodeInfo] = {

    // Holders, bind, templates, resources if the control has a name
    val holders = getControlNameOpt(controlElem).toList flatMap { controlName ⇒

      val buffer = mutable.ListBuffer[NodeInfo]()

      buffer ++=
        findDataHolders     (controlName) ++=
        findBindByName      (ctx.formDefinitionRootElem, controlName) ++=
        findTemplateInstance(ctx.formDefinitionRootElem, controlName) ++=
        findResourceHolders (controlName)

      buffer.result
    }

    // Prepend control element
    controlElem :: holders
  }

  // Rename a control with its holders, binds, etc. but *not* its nested iteration if any
  def renameControlIfNeeded(oldName: String, newName: String)(implicit ctx: FormBuilderDocContext): Option[UndoRename] =
    oldName != newName option {

      require(! newName.endsWith(DefaultIterationSuffix), s"control cannot end with `$DefaultIterationSuffix` (#3359)")

      findDataHolders(oldName)     foreach (rename(_, newName))
      findResourceHolders(oldName) foreach (rename(_, newName))

      renameBinds   (oldName, newName)
      renameControl (oldName, newName)
      renameTemplate(oldName, newName)

      findControlByName(ctx.formDefinitionRootElem, newName) foreach { newControl ⇒
        updateTemplatesCheckContainers(findAncestorRepeatNames(newControl).to[Set])
      }

      UndoRename(oldName, newName)
    }

  def renameControlIterationIfNeeded(
    oldControlName      : String,
    newControlName      : String,
    oldChildElementName : Option[String],
    newChildElementName : Option[String])(implicit
    ctx                 : FormBuilderDocContext
  ): Unit = {

    if (findControlByName(ctx.formDefinitionRootElem, oldControlName) exists controlRequiresNestedIterationElement) {

      val oldName = oldChildElementName getOrElse defaultIterationName(oldControlName)
      val newName = newChildElementName getOrElse defaultIterationName(newControlName)

      if (oldName != newName) {
        findDataHolders(oldName) foreach (rename(_, newName))
        renameBinds(oldName, newName)
        updateTemplates(None)
      }
    }
  }

  def renameControl(oldName: String, newName: String)(implicit ctx: FormBuilderDocContext): Unit =
    findControlByName(ctx.formDefinitionRootElem, oldName) foreach
      (renameControlByElement(_, newName, resourceNamesInUseForControl(newName)))

  def resourceNamesInUseForControl(controlName: String)(implicit ctx: FormBuilderDocContext): Set[String] =
    currentResources.child(controlName).child(*).map(_.localname).to[Set]

  // Rename the control (but NOT its holders, binds, etc.)
  def renameControlByElement(
    controlElement : NodeInfo,
    newName        : String,
    resourcesNames : Set[String])(implicit
    ctx            : FormBuilderDocContext
  ): Unit = {

    // Produce `section-` and `grid-` for sections and grids
    val newControlId =
      if (IsSection(controlElement))
        sectionId(newName)
      else if (IsGrid(controlElement))
        gridId(newName)
      else
        controlId(newName)

    // Set @id in any case, @ref value if present, @bind value if present
    ensureAttribute(controlElement, "id", newControlId)
    ensureAttribute(controlElement, "bind", bindId(newName))

    // Make the control point to its template if @template (or legacy @origin) is present
    for (attName ← List("template", "origin"))
      setvalue(controlElement /@ attName, makeInstanceExpression(templateId(newName)))

    // Set xf:label, xf:hint, xf:help and xf:alert @ref if present
    for {
      resourcePointer ← controlElement child *
      resourceName    = resourcePointer.localname
      if resourcesNames(resourceName)                             // We have a resource for this sub-element
      ref             ← resourcePointer.att("ref").headOption
      refVal          = ref.stringValue
      if refVal.isEmpty || refVal.startsWith("$form-resources")   // Don't overwrite ref pointing somewhere else
    } locally {
      setvalue(ref.toSeq, s"$$form-resources/$newName/$resourceName")
    }

    // If using a static itemset editor, set `xf:itemset/@ref` value
    // TODO: Does this work if the itemset points to the data?
    if (hasEditor(controlElement, "static-itemset"))
      setvalue(controlElement / "*:itemset" /@ "ref", s"$$form-resources/$newName/item")
  }

  // Rename a bind
  def renameBindElement(bindElement: NodeInfo, newName: String): Unit = {
    ensureAttribute(bindElement, "id",   bindId(newName))
    ensureAttribute(bindElement, "name", newName)
    ensureAttribute(bindElement, "ref",  newName)
  }

  // Rename a bind
  def renameBinds(oldName: String, newName: String)(implicit ctx: FormBuilderDocContext): Unit =
    findBindByName(ctx.formDefinitionRootElem, oldName) foreach (renameBindElement(_, newName))

  // Find or create a data holder for the given hierarchy of names
  private def ensureDataHolder(rootElem: NodeInfo, holders: Seq[(() ⇒ NodeInfo, Option[String])]) = {

    @tailrec def ensure(parents: Seq[NodeInfo], names: Iterator[(() ⇒ NodeInfo, Option[String])]): Seq[NodeInfo] =
      if (names.hasNext) {
        val (getHolder, precedingHolderName) = names.next()
        val holder = getHolder() // not ideal: this might create a NodeInfo just to check the name of the holder

        val children =
          for {
            parent ← parents
          } yield
            parent / * filter (_.name == holder.name) match {
              case Seq() ⇒
                // No holder exists so insert one
                insert(
                  into   = parent,
                  after  = parent / * filter (_.name == precedingHolderName.getOrElse("")),
                  origin = holder
                )
              case existing ⇒
                // At least one holder exists (can be more than one for repeats)
                existing

            }

        ensure(children.flatten, names)
      } else
        parents

    ensure(Seq(rootElem), holders.toIterator)
  }

  // Insert data and resource holders for all languages
  def insertHolderForAllLang(
    controlElement       : NodeInfo,
    dataHolder           : NodeInfo,
    resourceHolder       : NodeInfo,
    precedingControlName : Option[String])(implicit
    ctx                  : FormBuilderDocContext
  ): Unit = {

    // Create one holder per existing language
    val resourceHolders = (allResources(ctx.resourcesRootElem) attValue XMLLangQName) map (_ → List(resourceHolder))
    insertHolders(controlElement, List(dataHolder), resourceHolders, precedingControlName)
  }

  // Insert data and resource holders for all languages
  def insertHolders(
    controlElement       : NodeInfo,
    dataHolders          : Seq[NodeInfo],
    resourceHolders      : Seq[(String, Seq[NodeInfo])],
    precedingControlName : Option[String])(implicit
    ctx                  : FormBuilderDocContext
  ): Unit = {

    val containerNames = findContainerNamesForModel(controlElement)

    // Insert hierarchy of data holders
    // We pass a Seq of tuples, one part able to create missing data holders, the other one with optional previous
    // names. In practice, the ancestor holders should already exist.
    dataHolders foreach { dataHolder ⇒
      ensureDataHolder(
        ctx.dataRootElem,
        (containerNames map (name ⇒ (() ⇒ elementInfo(name), None))) :+ (() ⇒ dataHolder, precedingControlName)
      )
    }

    // Insert resources placeholders for all languages
    if (resourceHolders.nonEmpty) {
      val resourceHoldersMap = resourceHolders.toMap
      allResources(ctx.resourcesRootElem) foreach { resource ⇒
        val lang    = resource attValue XMLLangQName
        val holders = resourceHoldersMap.getOrElse(lang, resourceHolders.head._2)
        insert(
          into   = resource,
          after  = resource / * filter (_.name == precedingControlName.getOrElse("")),
          origin = holders
        )
      }
    }
  }

  // Update a mip for the given control, grid or section id
  // The bind is created if needed
  def updateMipAsAttributeOnly(
    controlName : String,
    mipName     : String,
    mipValue    : String)(implicit
    ctx         : FormBuilderDocContext
  ): Unit = {

    require(Model.AllMIPNames(mipName))
    val (mipAttQName, _) = mipToFBMIPQNames(Model.AllMIPsByName(mipName))

    findControlByName(ctx.formDefinitionRootElem, controlName) foreach { control ⇒

      // Get or create the bind element
      val bind = ensureBinds(findContainerNamesForModel(control) :+ controlName)

      // NOTE: It's hard to remove the namespace mapping once it's there, as in theory lots of
      // expressions and types could use it. So for now the mapping is never garbage collected.
      def isTypeString(value: String) =
        mipName == Model.Type.name &&
        valueNamespaceMappingScopeIfNeeded(bind, value).isDefined &&
        Set(XS_STRING_QNAME, XFORMS_STRING_QNAME)(bind.resolveQName(value))

      def isRequiredFalse(value: String) =
        mipName == Model.Required.name && value == "false()"

      def isWhitespacePreserve(value: String) =
        mipName == Model.Whitespace.name && value == Whitespace.Preserve.name

      def mustRemoveAttribute(value: String) =
        isTypeString(value) || isRequiredFalse(value) || isWhitespacePreserve(value)

      mipValue.trimAllToOpt match {
        case Some(normalizedMipValue) if ! mustRemoveAttribute(normalizedMipValue) ⇒
          ensureAttribute(bind, mipAttQName, normalizedMipValue)
        case _ ⇒
          delete(bind /@ mipAttQName)
      }
    }
  }

  // Return None if no namespace mapping is required OR none can be created
  def valueNamespaceMappingScopeIfNeeded(
    bind       : NodeInfo,
    qNameValue : String)(implicit
    ctx        : FormBuilderDocContext
  ): Option[(String, String)] = {

    val (prefix, _) = parseQName(qNameValue)

    def existingNSMapping =
      bind.namespaceMappings.toMap.get(prefix) map (prefix →)

    def newNSMapping = {
      // If there is no mapping and the schema prefix matches the prefix and a uri is found for the
      // schema, then insert a new mapping. We place it on the top-level bind so we don't have to insert
      // it repeatedly.
      val newURI =
        if (findSchemaPrefix(bind).contains(prefix))
          findSchemaNamespace(bind)
        else
          None

      newURI map { uri ⇒
        insert(into = ctx.topLevelBindElem.toList, origin = namespaceInfo(prefix, uri))
        prefix → uri
      }
    }

    if (prefix == "")
      None
    else
      existingNSMapping orElse newNSMapping
  }

  // Get the value of a MIP attribute if present
  def readMipAsAttributeOnly(controlName: String, mipName: String)(implicit ctx: FormBuilderDocContext): Option[String] = {
    require(Model.AllMIPNames(mipName))
    val (mipAttQName, _) = mipToFBMIPQNames(Model.AllMIPsByName(mipName))

    findBindByName(ctx.formDefinitionRootElem, controlName) flatMap (_ attValueOpt mipAttQName)
  }

  // Return (attQName, elemQName)
  def mipToFBMIPQNames(mip: Model.MIP): (QName, QName) =
    RewrittenMIPs.get(mip) match {
      case Some(qn) ⇒ qn        → qn
      case None     ⇒ mip.aName → mip.eName
    }

  def getAllNamesInUse(implicit ctx: FormBuilderDocContext): Set[String] =
    iterateNamesInUse(ctx.explicitFormDefinitionInstance.toRight(ctx.formDefinitionInstance.get), Some(ctx.dataRootElem)).to[Set]

  // Return all the controls in the view
  def getAllControlsWithIds(inDoc: NodeInfo): Seq[NodeInfo] =
    findFRBodyElem(inDoc) descendant * filter
      (e ⇒ isIdForControl(e.id))

  // Finds if a control uses a particular type of editor (say "static-itemset")
  def hasEditor(controlElement: NodeInfo, editor: String)(implicit ctx: FormBuilderDocContext): Boolean =
    FormBuilder.controlElementHasEditor(controlElement: NodeInfo, editor: String, ctx.componentBindings)

  // Find a given static control by name
  def findStaticControlByName(controlName: String)(implicit ctx: FormBuilderDocContext): Option[ElementAnalysis] = {
    val model = getFormModel
    val part = model.getStaticModel.part
    for {
      controlId ← findControlIdByName(ctx.formDefinitionRootElem, controlName)
      prefixedId = part.startScope.prefixedIdForStaticId(controlId)
      control ← Option(part.getControlAnalysis(prefixedId))
    } yield
      control
  }

  // Find the control by name (resolved from the top-level form model `fr-form-model`)
  def findConcreteControlByName(controlName: String)(implicit ctx: FormBuilderDocContext): Option[XFormsControl] = {
    val model = getFormModel
    for {
      controlId ← findControlIdByName(ctx.formDefinitionRootElem, controlName)
      control   ← model.container.resolveObjectByIdInScope(model.getEffectiveId, controlId) map (_.asInstanceOf[XFormsControl])
    } yield
      control
  }

  def setControlLabelOrHintOrText(
    controlName : String,
    lht         : String,
    value       : String,
    isHTML      : Boolean)(implicit
    ctx         : FormBuilderDocContext
  ): Unit = {
    setControlResource(controlName, lht, value.trimAllToEmpty)
    FormBuilder.setControlLHHATMediatype(controlName, lht, isHTML)
  }

  // Find a control's LHHA (there can be more than one for alerts)
  def getControlLHHAT(controlName: String, lhha: String)(implicit ctx: FormBuilderDocContext): Seq[NodeInfo] =
    findControlByName(ctx.formDefinitionRootElem, controlName).toList child ((if (lhha == "text") FR else XF) → lhha)

  // For a given control and LHHA type, whether the mediatype on the LHHA is HTML
  def isControlLHHATHTMLMediatype(controlName: String, lhha: String)(implicit ctx: FormBuilderDocContext): Boolean =
    hasHTMLMediatype(getControlLHHAT(controlName, lhha))

  // For a given control and LHHA type, set the mediatype on the LHHA to be HTML or plain text
  def setControlLHHATMediatype(controlName: String, lhha: String, isHTML: Boolean)(implicit ctx: FormBuilderDocContext): Unit =
    if (isHTML != isControlLHHATHTMLMediatype(controlName, lhha))
      setHTMLMediatype(getControlLHHAT(controlName, lhha), isHTML)

  // For a given control, whether the mediatype on itemset labels is HTML
  def isItemsetHTMLMediatype(controlName: String)(implicit ctx: FormBuilderDocContext): Boolean =
    hasHTMLMediatype(findControlByName(ctx.formDefinitionRootElem, controlName).toList child "itemset" child "label")

  def setHTMLMediatype(nodes: Seq[NodeInfo], isHTML: Boolean): Unit =
    nodes foreach { lhhaElement ⇒
      if (isHTML)
        insert(into = lhhaElement, origin = attributeInfo("mediatype", "text/html"))
      else
        delete(lhhaElement /@ "mediatype")
    }

  def ensureCleanLHHAElements(
    controlName : String,
    lhha        : String,
    count       : Int     = 1,
    replace     : Boolean = true)(implicit
    ctx         : FormBuilderDocContext
  ): Seq[NodeInfo] = {

    val inDoc = ctx.formDefinitionRootElem

    val control  = findControlByName(inDoc, controlName).get
    val existing = getControlLHHAT(controlName, lhha)

    if (replace)
      delete(existing)

    // Try to insert in the right position wrt other LHHA elements. If none, will be inserted as first
    // element.

    if (count > 0) {
      val newTemplates =
        if (count == 1) {
          def newTemplate: NodeInfo =
            <xf:lhha xmlns:xf="http://www.w3.org/2002/xforms"
                 ref={s"$$form-resources/$controlName/$lhha"}/>.copy(label = lhha)

          Seq(newTemplate)
        } else {
          def newTemplate(index: Int): NodeInfo =
            <xf:lhha xmlns:xf="http://www.w3.org/2002/xforms"
                 ref={s"$$form-resources/$controlName/$lhha[$index]"}/>.copy(label = lhha)

          1 to count map newTemplate
        }

      insertElementsImposeOrder(into = control, origin = newTemplates, LHHAInOrder)
    } else
      Nil
  }

  def removeLHHAElementAndResources(controlName: String, lhha: String)(implicit ctx: FormBuilderDocContext): Seq[NodeInfo] = {

    val inDoc = ctx.formDefinitionRootElem

    val control = findControlByName(inDoc, controlName).get

    val removedHolders = delete(lhhaHoldersForAllLangsUseDoc(controlName, lhha))
    val removedLHHA    = delete(control child ((if (lhha == "text") FR else XF) → lhha))

    removedHolders ++ removedLHHA
  }

  // Build an effective id for a given static id or return null (the empty sequence)
  def buildFormBuilderControlAbsoluteIdOrEmpty(staticId: String)(implicit ctx: FormBuilderDocContext): String =
    buildFormBuilderControlEffectiveId(staticId) map XFormsId.effectiveIdToAbsoluteId orNull

  def buildFormBuilderControlEffectiveId(staticId: String)(implicit ctx: FormBuilderDocContext): Option[String] =
    findInViewTryIndex(ctx.formDefinitionRootElem, staticId) map (DynamicControlId + COMPONENT_SEPARATOR + buildControlEffectiveId(_))

  // Set the control's items for all languages
  def setControlItems(controlName: String, items: NodeInfo)(implicit ctx: FormBuilderDocContext): Unit = {

    val addHints = FormBuilder.hasItemHintEditor(controlName)

    for ((lang, holder) ← FormRunner.findResourceHoldersWithLang(controlName, resourcesRoot)) {

      delete(holder / "item")

      val newItemElems =
        for (item ← items / "item")
        yield {
          <item>
            <label>{item / "label" filter (_.attValue("lang") == lang) stringValue}</label>
            {
              if (addHints)
                <hint>{ item / "hint"  filter (_.attValue("lang") == lang) stringValue}</hint>
            }
            <value>{item / "value" stringValue}</value>
          </item>
        }

      insert(into = holder, after = holder / *, origin = newItemElems map elemToNodeInfo toList)
    }
  }

  // Build an effective id for a given static id
  //
  // This assumes a certain hierarchy:
  //
  // - zero or more `*:section` containers
  // - zero or more `fr:grid` containers
  // - the only repeats are containers
  // - all containers must have stable (not automatically-generated by XForms) ids
  private def buildControlEffectiveId(control: NodeInfo): String = {
    val staticId = control.id

    // Ancestors from root to leaf except fb-body group if present
    val ancestorContainers =
      findAncestorContainersLeafToRoot(control, includeSelf = false).reverse filterNot isFBBody

    val containerIds = ancestorContainers map (_.id)
    val repeatDepth  = ancestorContainers count isRepeat

    def suffix = 1 to repeatDepth map (_ ⇒ 1) mkString REPEAT_INDEX_SEPARATOR_STRING
    val prefixedId = containerIds :+ staticId mkString XF_COMPONENT_SEPARATOR_STRING

    prefixedId + (if (repeatDepth == 0) "" else REPEAT_SEPARATOR + suffix)
  }
}
