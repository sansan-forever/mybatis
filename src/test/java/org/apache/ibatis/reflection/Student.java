package org.apache.ibatis.reflection;

/**
 * @Author: zhuping32
 * @Description:
 * @Date: 2021/5/31 10:39
 */

public class Student {

    private String clazz;

    private Integer age;

    private String name;

    public Student(String clazz) {
        this.clazz = clazz;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
