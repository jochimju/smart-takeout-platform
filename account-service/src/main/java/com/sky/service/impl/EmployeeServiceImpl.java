package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.PasswordConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.dto.EmployeePasswordDTO;
import com.sky.entity.Employee;
import com.sky.exception.AccountLockedException;
import com.sky.exception.AccountNotFoundException;
import com.sky.exception.PasswordEditFailedException;
import com.sky.exception.PasswordErrorException;
import com.sky.mapper.EmployeeMapper;
import com.sky.result.PageResult;
import com.sky.service.EmployeeService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class EmployeeServiceImpl implements EmployeeService {

    @Autowired
    private EmployeeMapper employeeMapper;
    @Autowired private com.sky.account.EmployeePasswords passwords;
    @org.springframework.beans.factory.annotation.Value("${sky.employee.initial-password:}") private String initialPassword;

    /**
     * 员工登录
     *
     * @param employeeLoginDTO
     * @return
     */
    public Employee login(EmployeeLoginDTO employeeLoginDTO) {
        String username = employeeLoginDTO.getUsername();
        String password = employeeLoginDTO.getPassword();

        //1、根据用户名查询数据库中的数据
        Employee employee = employeeMapper.getByUsername(username);

        //2、处理各种异常情况（用户名不存在、密码不对、账号被锁定）
        if (employee == null) {
            //账号不存在
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        //密码比对
        // TODO 后期需要进行md5加密，然后再进行比对
        if (!passwords.matches(password, employee.getPassword())) {
            //密码错误
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        if (employee.getStatus() == StatusConstant.DISABLE) {
            //账号被锁定
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        if (!passwords.current(employee.getPassword())) {
            String upgraded = passwords.encode(password);
            if (employeeMapper.changePassword(employee.getId(), employee.getPassword(), upgraded) != 1) {
                throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
            }
            employee.setPassword(upgraded);
            employee.setAuthVersion(employee.getAuthVersion() + 1);
        }

        //3、返回实体对象
        return employee;
    }


    /**
     * 启用禁用员工账号
     *
     * @param status
     * @param id
     */
    public void startOrStop(Integer status, Long id) {
        //最终我们希望执行的sql语句是 update employee set status = ? where id = ?
        //需要在Employee类上面加一个@Builder注解才能用这个方法
        Employee employee = Employee.builder()
                .status(status)
                .id(id)
                .build();
        //传统写法：
        //Employee employee = new Employee();
        //employee.setStatus(status);
        //employee.setId(id);


        //为了提高修改的通用性，我们把update方法写成动态的，即我们不止根据id修改status，还可以根据传进来的参数的不同修改多个字段。
        employeeMapper.update(employee);
    }

    /**
     * 根据id查询员工
     * @param id
     * @return
     */
    public Employee getById(Long id) {
        Employee employee = employeeMapper.getById(id);
        employee.setPassword("****");  //不想把密码返回给前端
        return employee;
    }

    /**
     * 编辑员工信息
     *
     * @param employeeDTO
     */
    public void update(EmployeeDTO employeeDTO) {  //将employeeDTO对象转成employee对象
        Employee employee = new Employee();
        BeanUtils.copyProperties(employeeDTO, employee);

//        employee.setUpdateTime(LocalDateTime.now());
//        employee.setUpdateUser(BaseContext.getCurrentId());

        employeeMapper.update(employee); //之前就设计好了这个动态查询的 update方法
    }

    /**
     * 新增员工
     * @param employeeDTO
     */
    @Override
    public void add(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();
        BeanUtils.copyProperties(employeeDTO, employee);


        employee.setStatus(StatusConstant.ENABLE);
        if (initialPassword == null || initialPassword.isBlank()) {
            throw new PasswordEditFailedException("请配置员工初始化密码后再创建账号");
        }
        employee.setPassword(passwords.encode(initialPassword));
        employee.setCreateTime(LocalDateTime.now());
        employee.setUpdateTime(LocalDateTime.now());
        employee.setCreateUser(BaseContext.getCurrentId());
        employee.setUpdateUser(BaseContext.getCurrentId());

        employeeMapper.insert(employee);
    }

    /**
     * 分页查询
     * @param employeePageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(EmployeePageQueryDTO employeePageQueryDTO) {
        PageHelper.startPage(employeePageQueryDTO.getPage(), employeePageQueryDTO.getPageSize());
        Page<Employee> page = employeeMapper.pageQuery(employeePageQueryDTO);
        long total = page.getTotal();
        List<Employee> records = page.getResult();
        return new PageResult(total, records);
    }

    /**
     * 修改当前登录员工密码：先校验原密码，再更新。
     * 密码存储方式与登录校验保持一致（当前库中为明文）。
     */
    @Override
    public void editPassword(EmployeePasswordDTO employeePasswordDTO) {
        if (employeePasswordDTO == null
                || employeePasswordDTO.getOldPassword() == null || employeePasswordDTO.getOldPassword().isBlank()
                || employeePasswordDTO.getNewPassword() == null || employeePasswordDTO.getNewPassword().isBlank()) {
            throw new PasswordEditFailedException(MessageConstant.PASSWORD_EDIT_FAILED);
        }
        Employee employee = employeeMapper.getById(BaseContext.getCurrentId());
        if (employee == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        if (!passwords.matches(employeePasswordDTO.getOldPassword(), employee.getPassword())) {
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }
        if (employeePasswordDTO.getNewPassword().length() < 8 || employeePasswordDTO.getNewPassword().length() > 128) {
            throw new PasswordEditFailedException("新密码需要8至128个字符");
        }
        if (employeeMapper.changePassword(employee.getId(), employee.getPassword(), passwords.encode(employeePasswordDTO.getNewPassword())) != 1) {
            throw new PasswordEditFailedException(MessageConstant.PASSWORD_EDIT_FAILED);
        }
    }

}
