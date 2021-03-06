package org.stepic.droid.store;

import org.stepic.droid.model.Lesson;
import org.stepic.droid.model.Section;

public interface IDownloadManager {

    void addSection(Section section);

    void addLesson(Lesson lesson);

    void cancelStep(long stepId);
}
