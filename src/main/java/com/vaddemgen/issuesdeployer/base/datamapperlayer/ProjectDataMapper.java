package com.vaddemgen.issuesdeployer.base.datamapperlayer;

import com.vaddemgen.issuesdeployer.base.businesslayer.model.Project;
import com.vaddemgen.issuesdeployer.base.businesslayer.model.group.Group;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

public interface ProjectDataMapper {

  Stream<Project> findProjectsByGroup(@NotNull Group superGroup);
}
