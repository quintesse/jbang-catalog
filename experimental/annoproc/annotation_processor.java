///usr/bin/env jbang "$0" "$@" ; exit $?
//FILES META-INF/services/javax.annotation.processing.Processor=annotation_processor.txt

import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;

public class annotation_processor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        System.out.println("Annotation processor called...");
        return false;
    }
}
