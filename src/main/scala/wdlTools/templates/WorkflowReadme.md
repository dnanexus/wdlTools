<%@ import val model workflow: wdlTools.syntax.AbstractSyntax.Workflow %>
<%@ val tasks: Map[String, String] %>
# Workflow ${name}

## Overview

## Tasks

#for (task <- tasks)
* [task._1](#task._2)
#end